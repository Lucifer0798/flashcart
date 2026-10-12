package com.flashcart.common.event.message;

import java.math.BigDecimal;
import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A refund the provider would not make was made some other way -- a transfer, the provider's console --
 * and an operator recorded it (ADR 0041). The money has reached the customer.
 *
 * <p>Its own event, not {@code PaymentRefunded}: that one says the provider reversed the capture, and a
 * provider statement and this platform's records would then disagree for reasons nobody could see. It is
 * published in the transaction that records the settlement, so it cannot announce one that rolled back.
 * See ADR 0050.
 *
 * @param reference what the operator recorded the money as moving under: a transfer id, a console refund
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundSettledOutside(EventMetadata metadata,
		String paymentId,
		String orderNumber,
		String customerId,
		BigDecimal amount,
		String currency,
		String reference) implements DomainEvent {

	public static final String TYPE = "RefundSettledOutside";


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
