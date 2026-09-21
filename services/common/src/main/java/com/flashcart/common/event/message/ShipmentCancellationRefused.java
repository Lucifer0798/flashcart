package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The consignment could not be stopped, and the goods are on their way or already delivered.
 *
 * <p>Carries the shipment's status so the order's history records <em>why</em> the cancellation
 * failed rather than merely that it did. "Refused" here is not an error: it is the warehouse
 * reporting a fact about the physical world, and the order goes back to {@code SHIPPED}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentCancellationRefused(EventMetadata metadata,
		String shipmentId,
		String orderNumber,
		String shipmentStatus,
		String reason) implements DomainEvent {

	public static final String TYPE = "ShipmentCancellationRefused";


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
