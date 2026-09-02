package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A shipment exists and has a tracking reference. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentCreated(EventMetadata metadata,
		String shipmentId,
		String orderNumber,
		String trackingNumber,
		String carrier) implements DomainEvent {

	public static final String TYPE = "ShipmentCreated";


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
