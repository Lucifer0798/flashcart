package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parcel arrived.
 *
 * <p>The event that was missing. Shipping has always recorded delivery on its own row, and nothing
 * told the order service, so {@code OrderStatus.DELIVERED} — the one successful terminal state this
 * platform has — was unreachable and every shipped order stayed {@code SHIPPED} forever.
 *
 * <p>Carries {@code deliveredAt} rather than letting the consumer use its own clock. The parcel
 * arrived when the warehouse says it did, and an order whose history reads a second or two later than
 * the shipment it describes is the sort of discrepancy that costs an afternoon to explain.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentDelivered(EventMetadata metadata,
		String shipmentId,
		String orderNumber,
		String trackingNumber,
		Instant deliveredAt) implements DomainEvent {

	public static final String TYPE = "ShipmentDelivered";


	@Override
	public String eventId() {
		return metadata.eventId();
	}

	@Override
	public String eventType() {
		return metadata.eventType();
	}

	@Override
	public String aggregateId() {
		return metadata.aggregateId();
	}

	@Override
	public Instant occurredAt() {
		return metadata.occurredAt();
	}

	@Override
	public String correlationId() {
		return metadata.correlationId();
	}
}
