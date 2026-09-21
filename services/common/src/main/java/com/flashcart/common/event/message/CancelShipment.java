package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stop this consignment if it has not left yet.
 *
 * <p>A request, not an instruction, which makes it the odd one out on a command topic. Every other
 * command here tells a service to do something it is able to do; this one asks a question only the
 * warehouse can answer, because a parcel already with the carrier cannot be recalled by a message.
 * Shipping answers with {@code ShipmentCancelled} or {@code ShipmentCancellationRefused}, and the
 * order waits in {@code CANCELLATION_REQUESTED} until it does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelShipment(EventMetadata metadata,
		String orderNumber,
		String reason) implements DomainEvent {

	public static final String TYPE = "CancelShipment";


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
