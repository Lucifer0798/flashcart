package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The provider said no.
 *
 * <p>Decisive, unlike {@link PaymentTimedOut}: nothing was charged, so releasing the held stock is
 * safe.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentFailed(EventMetadata metadata,
		String paymentId,
		String code,
		String reason) implements DomainEvent {

	public static final String TYPE = "PaymentFailed";


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
