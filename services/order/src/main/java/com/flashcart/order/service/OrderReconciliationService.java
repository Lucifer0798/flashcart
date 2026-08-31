package com.flashcart.order.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.client.InventoryClient;
import com.flashcart.order.client.InventoryUnavailableException;
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
 * Closes out orders whose reservation ran out before payment did anything.
 *
 * <p>This is the {@code RESERVATION_EXPIRED → RELEASE_INVENTORY} path from the spec, and it is the
 * compensation nobody triggers: the customer walked away, so no request will ever arrive to tidy up
 * after them. Without this job those orders sit in {@code RESERVED} forever, looking live to support
 * and to any report that counts them.
 *
 * <p>Inventory expires its own hold independently and will have returned the units already — this is
 * not what frees the stock. What it does is make the <em>order</em> agree with reality, which is
 * what the customer sees and what Phase 6's payment flow will consult. The release call is still
 * made, and is safe, because inventory's release is idempotent and a no-op on a hold that already
 * lapsed; belt and braces are cheap here and being wrong is not.
 *
 * <p>Note what it deliberately does not touch: an order in {@code PAYMENT_PENDING}. That has a
 * charge in flight, and reclaiming stock from underneath a payment that might yet succeed is exactly
 * the mistake the {@code PAYMENT_TIMEOUT} path exists to avoid. Phase 6 owns that case.
 */
@Service
public class OrderReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

	private final OrderRepository orders;
	private final OrderStatusChangeRepository history;
	private final InventoryClient inventory;
	private final OrderProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public OrderReconciliationService(OrderRepository orders, OrderStatusChangeRepository history,
			InventoryClient inventory, OrderProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.orders = orders;
		this.history = history;
		this.inventory = inventory;
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
				log.info("Reconciled {} order(s) whose reservation had expired", expired);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is not rescheduled by some executors, and a reconciler
			// that quietly stops leaves orders stuck in RESERVED with nobody watching. Swallow, log,
			// and try again on the next tick.
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
		// Re-checked rather than trusted: between the claim query and here, the customer may have
		// paid or cancelled. Expiring an order that has since moved on would be wrong twice over.
		if (order == null || !order.hasExpiredReservation(clock)) {
			return false;
		}

		try {
			// Idempotent, and a no-op if inventory already expired the hold itself — which it
			// usually will have, since its own sweeper runs on a shorter cycle.
			inventory.release(order.getReservationKey(), "reservation expired");
		}
		catch (InventoryUnavailableException ex) {
			// Leave the order alone and come back next tick. Marking it expired while unable to
			// confirm the stock is back would let the order and inventory disagree, which is the
			// one outcome this job exists to prevent.
			log.warn("Inventory unavailable while reconciling order {}; retrying next tick",
					order.getOrderNumber());
			return false;
		}

		transactions.executeWithoutResult(status -> {
			Order current = orders.findById(orderId).orElseThrow();
			if (current.getStatus() != OrderStatus.RESERVED) {
				return;
			}
			history.save(current.transitionTo(OrderStatus.RESERVATION_EXPIRED,
					"reservation expired before payment", CorrelationId.current()));
			// Straight on to CANCELLED, now that the stock is demonstrably back. The intermediate
			// state is persisted rather than skipped so the history shows *why* it was cancelled.
			history.save(current.transitionTo(OrderStatus.CANCELLED,
					"reservation expired before payment", CorrelationId.current()));
		});

		log.debug("Order {} expired and cancelled", order.getOrderNumber());
		return true;
	}
}
