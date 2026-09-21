package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The consignment was stopped before it was dispatched. Nothing has left the warehouse. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentCancelled(EventMetadata metadata,
		String shipmentId,
		String orderNumber,
		String trackingNumber) implements DomainEvent {

	public static final String TYPE = "ShipmentCancelled";


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
