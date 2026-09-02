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
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.service.OrderSaga;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Wires inbound events to the saga.
 *
 * <p>Nothing but plumbing on purpose — every decision lives in {@link OrderSaga}, so the sequence and
 * its compensations can be read in one place rather than reconstructed from seven listener methods.
 *
 * <p>Each handler restores the correlation id into the MDC first. A Kafka consumer thread never saw
 * the HTTP request that started the checkout, so without this every log line the saga writes would be
 * unattributable — which defeats the point of having carried the id across the bus at all.
 */
@Component
public class OrderEventListener {

	private final OrderSaga saga;

	public OrderEventListener(OrderSaga saga) {
		this.saga = saga;
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "inventoryReservedFactory",
			groupId = OrderKafkaConfig.GROUP + "-inventory-reserved")
	public void onInventoryReserved(InventoryReserved event) {
		withCorrelationId(event.correlationId(), () ->
				saga.onInventoryReserved(UUID.fromString(event.aggregateId()), event.expiresAt()));
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS,
			containerFactory = "inventoryReservationFailedFactory",
			groupId = OrderKafkaConfig.GROUP + "-inventory-failed")
	public void onReservationFailed(InventoryReservationFailed event) {
		withCorrelationId(event.correlationId(), () ->
				saga.onReservationFailed(UUID.fromString(event.aggregateId()), event.code(),
						event.reason()));
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "reservationExpiredFactory",
			groupId = OrderKafkaConfig.GROUP + "-reservation-expired")
	public void onReservationExpired(ReservationExpired event) {
		// The reservation key is the order id, which is why this event can be routed without a lookup.
		withCorrelationId(event.correlationId(), () ->
				saga.onReservationExpired(UUID.fromString(event.reservationKey())));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentCompletedFactory",
			groupId = OrderKafkaConfig.GROUP + "-payment-completed")
	public void onPaymentCompleted(PaymentCompleted event) {
		withCorrelationId(event.correlationId(), () ->
				saga.onPaymentCompleted(UUID.fromString(event.aggregateId()), event.paymentId()));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentFailedFactory",
			groupId = OrderKafkaConfig.GROUP + "-payment-failed")
	public void onPaymentFailed(PaymentFailed event) {
		withCorrelationId(event.correlationId(), () ->
				saga.onPaymentFailed(UUID.fromString(event.aggregateId()), event.code(), event.reason()));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentTimedOutFactory",
			groupId = OrderKafkaConfig.GROUP + "-payment-timeout")
	public void onPaymentTimedOut(PaymentTimedOut event) {
		withCorrelationId(event.correlationId(), () ->
				saga.onPaymentTimedOut(UUID.fromString(event.aggregateId())));
	}

	@KafkaListener(topics = Topics.SHIPPING_EVENTS, containerFactory = "shipmentCreatedFactory",
			groupId = OrderKafkaConfig.GROUP + "-shipment-created")
	public void onShipmentCreated(ShipmentCreated event) {
		withCorrelationId(event.correlationId(), () ->
				saga.onShipmentCreated(UUID.fromString(event.aggregateId()), event.trackingNumber()));
	}

	private static void withCorrelationId(String correlationId, Runnable work) {
		if (correlationId != null) {
			MDC.put(CorrelationId.MDC_KEY, correlationId);
		}
		try {
			work.run();
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}
}
