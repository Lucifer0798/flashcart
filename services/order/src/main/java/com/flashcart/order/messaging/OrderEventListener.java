package com.flashcart.order.messaging;

import java.util.UUID;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.InventoryReservationFailed;
import com.flashcart.common.event.message.InventoryReserved;
import com.flashcart.common.event.message.PaymentCompleted;
import com.flashcart.common.event.message.PaymentFailed;
import com.flashcart.common.event.message.PaymentTimedOut;
import com.flashcart.common.event.message.ReservationExpired;
import com.flashcart.common.event.message.ShipmentCreated;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.order.service.OrderSaga;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Wires inbound events to the saga, exactly once each.
 *
 * <p>Nothing but plumbing on purpose — every decision lives in {@link OrderSaga}, so the sequence and
 * its compensations can be read in one place rather than reconstructed from seven listener methods.
 *
 * <p>Each handler runs through {@link IdempotentHandler}, which claims the event id and runs the work
 * in one transaction. That replaces the Phase 5 arrangement where duplicates were absorbed by the
 * state machine declining an illegal transition — ADR 0014 called that interim precisely because it
 * could not tell a harmless duplicate from a real bug.
 *
 * <p>The saga's own {@code advance} guard stays, and is now doing only the job it should have been
 * doing all along: rejecting transitions that genuinely are not legal, rather than doubling as a
 * deduplicator.
 */
@Component
public class OrderEventListener {

	/** Names this consumer in {@code processed_events}, so other services dedupe independently. */
	private static final String CONSUMER = "order-saga";

	private final OrderSaga saga;
	private final IdempotentHandler handler;

	public OrderEventListener(OrderSaga saga, IdempotentHandler handler) {
		this.saga = saga;
		this.handler = handler;
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "inventoryReservedFactory",
			groupId = OrderKafkaConfig.GROUP + "-inventory-reserved")
	public void onInventoryReserved(InventoryReserved event) {
		handler.handle(event, CONSUMER, () ->
				saga.onInventoryReserved(UUID.fromString(event.aggregateId()), event.expiresAt()));
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS,
			containerFactory = "inventoryReservationFailedFactory",
			groupId = OrderKafkaConfig.GROUP + "-inventory-failed")
	public void onReservationFailed(InventoryReservationFailed event) {
		handler.handle(event, CONSUMER, () ->
				saga.onReservationFailed(UUID.fromString(event.aggregateId()), event.code(),
						event.reason()));
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "reservationExpiredFactory",
			groupId = OrderKafkaConfig.GROUP + "-reservation-expired")
	public void onReservationExpired(ReservationExpired event) {
		// The reservation key is the order id, which is why this event routes without a lookup.
		handler.handle(event, CONSUMER, () ->
				saga.onReservationExpired(UUID.fromString(event.reservationKey())));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentCompletedFactory",
			groupId = OrderKafkaConfig.GROUP + "-payment-completed")
	public void onPaymentCompleted(PaymentCompleted event) {
		handler.handle(event, CONSUMER, () ->
				saga.onPaymentCompleted(UUID.fromString(event.aggregateId()), event.paymentId()));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentFailedFactory",
			groupId = OrderKafkaConfig.GROUP + "-payment-failed")
	public void onPaymentFailed(PaymentFailed event) {
		handler.handle(event, CONSUMER, () ->
				saga.onPaymentFailed(UUID.fromString(event.aggregateId()), event.code(), event.reason()));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentTimedOutFactory",
			groupId = OrderKafkaConfig.GROUP + "-payment-timeout")
	public void onPaymentTimedOut(PaymentTimedOut event) {
		handler.handle(event, CONSUMER, () ->
				saga.onPaymentTimedOut(UUID.fromString(event.aggregateId())));
	}

	@KafkaListener(topics = Topics.SHIPPING_EVENTS, containerFactory = "shipmentCreatedFactory",
			groupId = OrderKafkaConfig.GROUP + "-shipment-created")
	public void onShipmentCreated(ShipmentCreated event) {
		handler.handle(event, CONSUMER, () ->
				saga.onShipmentCreated(UUID.fromString(event.aggregateId()), event.trackingNumber()));
	}
}
