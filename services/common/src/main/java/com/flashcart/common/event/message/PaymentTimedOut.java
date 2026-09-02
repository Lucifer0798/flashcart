package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The provider said nothing in time.
 *
 * <p>The genuinely hard one, and a separate event type for exactly that reason: the charge may still
 * land. Releasing the stock here could sell the same unit twice and then owe a refund, so this goes
 * to reconciliation rather than to compensation — the {@code PAYMENT_TIMEOUT} branch the order state
 * machine has carried since Phase 1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentTimedOut(EventMetadata metadata,
		String paymentId) implements DomainEvent {

	public static final String TYPE = "PaymentTimedOut";


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
