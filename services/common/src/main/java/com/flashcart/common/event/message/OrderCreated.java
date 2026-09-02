package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An order exists and has been priced. Nothing is held yet. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreated(EventMetadata metadata,
		String orderNumber,
		String customerId,
		String flashSaleId,
		java.math.BigDecimal total,
		String currency,
		java.util.List<OrderLineMessage> lines) implements DomainEvent {

	public static final String TYPE = "OrderCreated";


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
