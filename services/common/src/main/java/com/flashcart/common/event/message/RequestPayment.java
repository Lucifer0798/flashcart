package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Take the money for an order.
 *
 * <p>{@code idempotencyKey} is the order id: a payment provider charged twice for one order is the
 * most expensive possible consequence of at-least-once delivery, so this key travels all the way
 * down to the provider call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestPayment(EventMetadata metadata,
		String orderNumber,
		String customerId,
		java.math.BigDecimal amount,
		String currency,
		String idempotencyKey) implements DomainEvent {

	public static final String TYPE = "RequestPayment";


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
