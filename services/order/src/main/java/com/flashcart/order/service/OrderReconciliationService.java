package com.flashcart.order.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.config.OrderProperties;
import com.flashcart.order.domain.Order;
import com.flashcart.order.repository.OrderRepository;
import com.flashcart.order.repository.OrderStatusChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The backstop for orders whose reservation lapsed and whose expiry event never arrived.
 *
 * <p>Inventory publishes {@code ReservationExpired} when it reclaims a hold, and the saga acts on it.
 * This job exists because that is not enough on its own: an event can be lost between a publish that
 * failed after the database committed and Phase 8's outbox closing that gap, and a consumer can be
 * down long enough for a message to age out. Without a backstop, such an order sits in
 * {@code RESERVED} forever — looking live to the customer and to every report that counts it.
 *
 * <p>It works from the order's own mirrored {@code reservationExpiresAt} rather than from anything
 * inventory says, which is the point: it has to be able to reach the right conclusion when nothing
 * arrives at all.
 *
 * <p>It sends a release command as it goes. That is belt and braces — inventory will almost always
 * have reclaimed the units already, and its release is idempotent and a no-op against a hold that
 * lapsed — but "almost always" is not a basis for leaving stock unaccounted for.
 *
 * <p>Note what it deliberately does not touch: an order in {@code PAYMENT_PENDING}. That has a charge
 * in flight, and reclaiming stock from underneath a payment that might yet succeed is exactly the
 * mistake the {@code PAYMENT_TIMEOUT} path exists to avoid.
 */
@Service
public class OrderReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

	private final OrderRepository orders;
	private final OrderStatusChangeRepository history;
	private final OrderSaga saga;
	private final OrderProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public OrderReconciliationService(OrderRepository orders, OrderStatusChangeRepository history,
			OrderSaga saga, OrderProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.orders = orders;
		this.history = history;
		this.saga = saga;
		this.properties = properties;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Scheduled(
			fixedDelayString = "${flashcart.order.reconciler.fixed-delay:PT15S}",
			initialDelayString = "${flashcart.order.reconciler.initial-delay:PT20S}")
	public void reconcile() {
		if (!properties.reconciler().enabled()) {
			return;
		}
		try {
			int expired = reconcileBatch();
			if (expired > 0) {
				log.info("Reconciled {} order(s) whose reservation had expired without an event", expired);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is not rescheduled by some executors, and a reconciler
			// that quietly stops leaves orders stuck in RESERVED with nobody watching.
			log.error("Order reconciliation failed; will retry on the next tick", ex);
		}
	}

	/** One batch, exposed so tests can drive it deterministically instead of waiting on a timer. */
	public int reconcileBatch() {
		List<UUID> candidates = transactions.execute(status ->
				orders.claimExpiredReservations(clock.instant(), properties.reconciler().batchSize()));

		int reconciled = 0;
		for (UUID orderId : candidates) {
			if (expire(orderId)) {
				reconciled++;
			}
		}
		return reconciled;
	}

	private boolean expire(UUID orderId) {
		Order order = transactions.execute(status -> orders.findById(orderId).orElse(null));
		// Re-checked rather than trusted: between the claim query and here, the expiry event may
		// have arrived, or the customer may have cancelled.
		if (order == null || !order.hasExpiredReservation(clock)) {
			return false;
		}

		saga.releaseInventory(order, "reservation expired (reconciler)");

		transactions.executeWithoutResult(status -> {
			Order current = orders.findById(orderId).orElseThrow();
			if (current.getStatus() != OrderStatus.RESERVED) {
				return;
			}
			history.save(current.transitionTo(OrderStatus.RESERVATION_EXPIRED,
					"reservation expired; no event received", CorrelationId.current()));
			// The intermediate state is persisted rather than skipped, so the history shows *why*
			// the order was cancelled and not merely that it was.
			history.save(current.transitionTo(OrderStatus.CANCELLED,
					"reservation expired; no event received", CorrelationId.current()));
		});

		log.debug("Order {} expired and cancelled by the reconciler", order.getOrderNumber());
		return true;
	}
}
