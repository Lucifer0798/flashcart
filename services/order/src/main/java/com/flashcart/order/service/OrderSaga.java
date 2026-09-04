package com.flashcart.order.service;

import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.CommitInventory;
import com.flashcart.common.event.message.CreateShipment;
import com.flashcart.common.event.message.OrderCancelled;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.common.event.message.OrderLineMessage;
import com.flashcart.common.event.message.ReleaseInventory;
import com.flashcart.common.event.message.RequestPayment;
import com.flashcart.common.event.message.ReserveInventory;
import com.flashcart.common.order.OrderStateMachine;
import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.domain.Order;
import com.flashcart.order.repository.OrderRepository;
import com.flashcart.order.repository.OrderStatusChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga: what happens next, and what happens instead when it goes wrong.
 *
 * <h2>Orchestrated, not choreographed</h2>
 *
 * The order service decides the sequence and sends commands; inventory, payment and shipping react
 * and report. The alternative — each service listening for the previous one's event and deciding for
 * itself — is more decoupled and has a real cost: the overall sequence would exist nowhere in the
 * codebase, and "why is this order stuck" would only be answerable by reading four services' logs
 * side by side. Here the whole flow is one readable class, next to the state machine that constrains
 * it.
 *
 * <h2>The happy path</h2>
 *
 * <pre>
 * order placed        ─▶ ReserveInventory   ─▶ (InventoryReserved)   ─▶ RESERVED
 *                     ─▶ RequestPayment     ─▶ (PaymentCompleted)    ─▶ PAID
 *                     ─▶ CommitInventory + CreateShipment            ─▶ FULFILLING
 *                                           ─▶ (ShipmentCreated)     ─▶ SHIPPED
 * </pre>
 *
 * <h2>And the compensations</h2>
 *
 * <pre>
 * InventoryReservationFailed ─▶ CANCELLED                       (nothing to undo)
 * PaymentFailed              ─▶ PAYMENT_FAILED ─▶ ReleaseInventory ─▶ CANCELLED
 * ReservationExpired         ─▶ RESERVATION_EXPIRED             ─▶ CANCELLED
 * PaymentTimedOut            ─▶ PAYMENT_TIMEOUT                 ─▶ reconciliation, never auto-release
 * </pre>
 *
 * <h2>Idempotency without a dedup table</h2>
 *
 * Every handler goes through {@link #advance}, which checks the state machine and <em>ignores</em> a
 * transition that is not legal from where the order currently is. That is what makes redelivery
 * harmless: a second {@code InventoryReserved} finds the order already {@code RESERVED},
 * {@code RESERVED → RESERVED} is not an edge, and the message is acknowledged and dropped rather
 * than throwing and eventually dead-lettering a message that was simply a duplicate.
 *
 * <p>Note it <em>ignores</em> rather than throws — the distinction is the whole point. Throwing
 * would be correct for a genuinely impossible transition and catastrophic for an ordinary duplicate,
 * and from inside a consumer the two are indistinguishable. Phase 8's processed-event table makes
 * this explicit rather than inferred.
 */
@Service
public class OrderSaga {

	private static final Logger log = LoggerFactory.getLogger(OrderSaga.class);

	private final OrderRepository orders;
	private final OrderStatusChangeRepository history;
	private final EventPublisher events;
	private final MeterRegistry meters;

	public OrderSaga(OrderRepository orders, OrderStatusChangeRepository history, EventPublisher events,
			MeterRegistry meters) {
		this.orders = orders;
		this.history = history;
		this.events = events;
		this.meters = meters;
	}

	// --- outbound: the commands this saga issues ---------------------------------------------------

	/** Step one. Sent immediately after the order is persisted as {@code CREATED}. */
	public void requestReservation(Order order) {
		events.publish(Topics.INVENTORY_COMMANDS, new ReserveInventory(
				EventMetadata.of(ReserveInventory.TYPE, order.getId()),
				order.getReservationKey(),
				order.getCustomerId(),
				order.getFlashSaleId() == null ? null : order.getFlashSaleId().toString(),
				order.getLines().stream()
						.map(line -> new OrderLineMessage(line.getSku(), line.getQuantity()))
						.toList()));
	}

	public void requestPayment(Order order) {
		events.publish(Topics.PAYMENT_COMMANDS, new RequestPayment(
				EventMetadata.of(RequestPayment.TYPE, order.getId()),
				order.getOrderNumber(),
				order.getCustomerId(),
				order.getTotal(),
				order.getCurrency(),
				// The order id again: a provider charged twice for one order is the most expensive
				// possible consequence of at-least-once delivery, so this key goes all the way down.
				order.getId().toString()));
	}

	public void releaseInventory(Order order, String reason) {
		events.publish(Topics.INVENTORY_COMMANDS, new ReleaseInventory(
				EventMetadata.of(ReleaseInventory.TYPE, order.getId()),
				order.getReservationKey(), reason));
	}

	// --- inbound: what the saga does when something reports back ------------------------------------

	@Transactional
	public void onInventoryReserved(UUID orderId, java.time.Instant expiresAt) {
		advance(orderId, OrderStatus.RESERVED, "stock held by inventory", order -> {
			order.setReservationExpiresAt(expiresAt);
			// Straight on to asking for the money. Nothing waits for a human here — the hold is
			// ticking, and every second spent not charging is a second closer to losing it.
			requestPayment(order);
			history.save(order.transitionTo(OrderStatus.PAYMENT_PENDING, "payment requested",
					CorrelationId.current()));
		});
	}

	@Transactional
	public void onReservationFailed(UUID orderId, String code, String reason) {
		// Nothing was held, so there is nothing to compensate — straight to cancelled, carrying
		// inventory's own code so support can tell "sold out" from "you already bought one".
		advance(orderId, OrderStatus.CANCELLED, "%s: %s".formatted(code, reason), order ->
				publishCancelled(order, code));
	}

	@Transactional
	public void onReservationExpired(UUID orderId) {
		advance(orderId, OrderStatus.RESERVATION_EXPIRED, "reservation expired before payment", order -> {
			// Inventory has already reclaimed the units — that is what it just told us — so this
			// only has to make the order agree, then finish.
			history.save(order.transitionTo(OrderStatus.CANCELLED, "reservation expired before payment",
					CorrelationId.current()));
			publishCancelled(order, "RESERVATION_EXPIRED");
		});
	}

	@Transactional
	public void onPaymentCompleted(UUID orderId, String paymentId) {
		advance(orderId, OrderStatus.PAID, "payment " + paymentId + " completed", order -> {
			// Only now do the units actually leave the warehouse.
			events.publish(Topics.INVENTORY_COMMANDS, new CommitInventory(
					EventMetadata.of(CommitInventory.TYPE, order.getId()), order.getReservationKey()));

			events.publish(Topics.SHIPPING_COMMANDS, new CreateShipment(
					EventMetadata.of(CreateShipment.TYPE, order.getId()),
					order.getOrderNumber(),
					order.getCustomerId(),
					order.getLines().stream()
							.map(line -> new OrderLineMessage(line.getSku(), line.getQuantity()))
							.toList()));

			history.save(order.transitionTo(OrderStatus.FULFILLING, "payment settled; fulfilling",
					CorrelationId.current()));

			events.publish(Topics.ORDER_EVENTS, new OrderConfirmed(
					EventMetadata.of(OrderConfirmed.TYPE, order.getId()),
					order.getOrderNumber(), order.getCustomerId()));
		});
	}

	@Transactional
	public void onPaymentFailed(UUID orderId, String code, String reason) {
		advance(orderId, OrderStatus.PAYMENT_FAILED, reason, order -> {
			// The provider declined, so nothing was charged and the stock is safe to give back.
			releaseInventory(order, "payment declined");
			history.save(order.transitionTo(OrderStatus.CANCELLED, reason, CorrelationId.current()));
			publishCancelled(order, code);
		});
	}

	@Transactional
	public void onPaymentTimedOut(UUID orderId) {
		// Deliberately terminal for now. The charge may still land, so releasing the stock could sell
		// the same unit twice and then owe a refund. The order stops here and waits for
		// reconciliation, which is the only actor that can find out what actually happened.
		advance(orderId, OrderStatus.PAYMENT_TIMEOUT,
				"payment provider did not answer; awaiting reconciliation", order -> { });
	}

	@Transactional
	public void onShipmentCreated(UUID orderId, String trackingNumber) {
		advance(orderId, OrderStatus.SHIPPED, "handed to carrier, tracking " + trackingNumber,
				order -> { });
	}

	// --- the guard every handler goes through -------------------------------------------------------

	/**
	 * Apply {@code next} if the state machine allows it from where the order is now; otherwise do
	 * nothing at all.
	 *
	 * <p>Ignoring rather than throwing is what makes these handlers safe under redelivery. It does
	 * mean a genuinely impossible transition is also silently ignored, which is why it is logged at
	 * {@code info} with both states — that log line is the trace to follow if an order ever stops
	 * where it should not have.
	 */
	private void advance(UUID orderId, OrderStatus next, String reason,
			java.util.function.Consumer<Order> thenDo) {
		Order order = orders.findById(orderId).orElse(null);
		if (order == null) {
			log.warn("Ignoring {} for unknown order {}", next, orderId);
			return;
		}
		if (!OrderStateMachine.canTransition(order.getStatus(), next)) {
			log.info("Ignoring {} for order {}: not reachable from {} (most likely a redelivery)",
					next, order.getOrderNumber(), order.getStatus());
			// Since Phase 8 this should be close to zero: processed_events catches duplicates before
			// they ever reach here, so what is left is transitions that genuinely should not have
			// been attempted. A rising rate is a real anomaly, which is precisely what this guard
			// could not tell you when it was also the deduplicator.
			Counter.builder("flashcart.saga.transitions.declined")
					.description("Transitions the state machine refused as unreachable")
					.tag("from", order.getStatus().name())
					.tag("to", next.name())
					.register(meters)
					.increment();
			return;
		}

		// Tagged from -> to rather than counted per handler, so the compensation paths are legible
		// without knowing the code: PAYMENT_PENDING -> PAYMENT_FAILED and RESERVED ->
		// RESERVATION_EXPIRED are the two that say the platform is losing orders, and to what.
		Counter.builder("flashcart.saga.transitions")
				.description("Order state transitions the saga applied")
				.tag("from", order.getStatus().name())
				.tag("to", next.name())
				.register(meters)
				.increment();

		history.save(order.transitionTo(next, reason, CorrelationId.current()));
		thenDo.accept(order);
	}

	private void publishCancelled(Order order, String reason) {
		events.publish(Topics.ORDER_EVENTS, new OrderCancelled(
				EventMetadata.of(OrderCancelled.TYPE, order.getId()),
				order.getOrderNumber(), reason));
	}
}
