package com.flashcart.common.event.message;

import java.math.BigDecimal;
import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Give the money back for an order that was cancelled after it was paid.
 *
 * <p>{@code idempotencyKey} is <em>not</em> the order id. That key already belongs to the charge, and
 * a refund keyed the same way would look to the provider like a repeat of the capture. It is
 * {@code refund:&lt;order id&gt;} instead — distinct from the charge, and still derived from the order
 * so a redelivered command cannot become a second refund.
 *
 * <p>{@code amount} is carried for the audit trail rather than to be trusted: payment refunds what it
 * actually captured, which it already knows. A command that could name its own amount would be a way
 * to refund more than was ever taken.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundPayment(EventMetadata metadata,
		String orderNumber,
		BigDecimal amount,
		String currency,
		String reason,
		String idempotencyKey) implements DomainEvent {

	public static final String TYPE = "RefundPayment";


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
