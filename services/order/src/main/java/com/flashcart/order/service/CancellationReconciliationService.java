package com.flashcart.order.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.order.config.OrderProperties;
import com.flashcart.order.domain.Order;
import com.flashcart.order.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The backstop for a cancellation nobody answered.
 *
 * <p>ADR 0030 made cancelling a paid order a question put to shipping, and left this open: if
 * shipping's answer is lost, the order sits in {@code CANCELLATION_REQUESTED} forever. That is a
 * non-terminal state with no timer behind it — the customer has asked to cancel and will never be
 * told either way, and the money is neither refunded nor confirmed as kept.
 *
 * <h2>Why this one can only repeat the question</h2>
 *
 * {@link OrderReconciliationService} is the other backstop in this service, and the difference
 * between them is the whole reason this is a separate class. That one <em>reaches a conclusion</em>:
 * it knows when a reservation lapsed because the order mirrors {@code reservationExpiresAt}, so when
 * nothing arrives it can decide for itself and cancel.
 *
 * <p>This one holds no such fact. Whether the parcel has left is shipping's alone — that is precisely
 * what ADR 0030 decided — so a backstop that concluded anything after a timeout would be inventing
 * the answer. Timing out to {@code CANCELLED} would refund parcels already in transit; timing out to
 * {@code SHIPPED} would keep the money for goods still on a shelf. Both are the mistake the design
 * exists to avoid, arrived at by waiting instead of by deciding.
 *
 * <p>So it re-sends {@code CancelShipment} and waits again. Shipping's handler is idempotent in all
 * three directions — an already-cancelled consignment re-publishes its cancellation, a dispatched one
 * re-publishes its refusal, and one still sitting in {@code CREATED} is cancelled as the first
 * command should have done — so asking again is safe however far the first attempt got.
 *
 * <h2>It cannot fix a shipping that is not listening</h2>
 *
 * Re-asking works when a message was lost. It does nothing when shipping is down or its consumer is
 * broken, and in that case the same orders come round again every timeout — once per timeout, not
 * once per tick, because a re-ask touches the row and restarts the clock it is measured against.
 *
 * <p>That is what {@code flashcart.order.cancellations.unanswered} is for: a single re-ask is not a
 * fault, but a rate that does not fall back to zero means the platform is repeatedly asking a
 * question nothing is answering, which is wrong on its own terms and alerts.
 */
@Service
public class CancellationReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(CancellationReconciliationService.class);

	private final OrderRepository orders;
	private final OrderSaga saga;
	private final OrderProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;
	private final Counter unanswered;

	public CancellationReconciliationService(OrderRepository orders, OrderSaga saga,
			OrderProperties properties, Clock clock, PlatformTransactionManager transactionManager,
			MeterRegistry meters) {
		this.orders = orders;
		this.saga = saga;
		this.properties = properties;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
		this.unanswered = Counter.builder("flashcart.order.cancellations.unanswered")
				.description("Cancellation requests re-sent because shipping never answered the first")
				.register(meters);
	}

	@Scheduled(
			fixedDelayString = "${flashcart.order.reconciler.fixed-delay:PT15S}",
			initialDelayString = "${flashcart.order.reconciler.initial-delay:PT20S}")
	public void reconcile() {
		if (!properties.reconciler().enabled()) {
			return;
		}
		try {
			int chased = reconcileBatch();
			if (chased > 0) {
				// Warn, not info. Every one of these is a customer who asked to cancel and has been
				// told nothing since.
				log.warn("Re-sent {} cancellation request(s) that shipping had not answered", chased);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is not rescheduled by some executors, and a backstop
			// that quietly stops is worse than no backstop, because it looks like one.
			log.error("Cancellation reconciliation failed; will retry on the next tick", ex);
		}
	}

	/** One batch, exposed so tests can drive it deterministically instead of waiting on a timer. */
	public int reconcileBatch() {
		Instant cutoff = clock.instant().minus(properties.reconciler().cancellationTimeout());
		List<UUID> candidates = transactions.execute(status ->
				orders.claimUnansweredCancellations(cutoff, properties.reconciler().batchSize()));

		int chased = 0;
		for (UUID orderId : candidates) {
			if (reask(orderId)) {
				chased++;
			}
		}
		return chased;
	}

	private boolean reask(UUID orderId) {
		Order order = transactions.execute(status -> orders.findById(orderId).orElse(null));
		// Re-checked rather than trusted: between the claim query and here the answer may have
		// arrived, which is the outcome this job is hoping for.
		if (order == null || order.getStatus() != OrderStatus.CANCELLATION_REQUESTED) {
			return false;
		}

		// No transition, deliberately. The order is already in the state that says what is true --
		// it is waiting -- and writing a history entry per tick would bury the one that matters
		// under a running commentary on how long it has been waiting.
		saga.requestShipmentCancellation(order, "cancellation request re-sent; no answer received");

		// The row is touched even though nothing about the order changed, because the claim query
		// reads updated_at as "how long we have been waiting" and this restarts that clock. Without
		// it the order stays past the cutoff forever and is re-asked on every tick.
		transactions.executeWithoutResult(status ->
				orders.markCancellationReasked(orderId, clock.instant()));
		unanswered.increment();

		log.info("Re-asked shipping about order {}", order.getOrderNumber());
		return true;
	}
}
