package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Paid for, stock committed, and on its way to being fulfilled. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmed(EventMetadata metadata,
		String orderNumber,
		String customerId) implements DomainEvent {

	public static final String TYPE = "OrderConfirmed";


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
