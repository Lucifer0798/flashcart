package com.flashcart.common.event.message;

import java.math.BigDecimal;
import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The money went back. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRefunded(EventMetadata metadata,
		String paymentId,
		BigDecimal amount,
		String currency,
		String providerReference) implements DomainEvent {

	public static final String TYPE = "PaymentRefunded";


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
