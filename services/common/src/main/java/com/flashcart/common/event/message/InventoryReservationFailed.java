package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stock could not be held, and this is exactly why.
 *
 * <p>{@code code} is inventory's own — {@code INSUFFICIENT_STOCK},
 * {@code SALE_ALLOCATION_EXHAUSTED}, {@code CUSTOMER_LIMIT_EXCEEDED}. Carried rather than flattened
 * because all three read as "sold out" to a shopper and mean entirely different things to an
 * operator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationFailed(EventMetadata metadata,
		String reservationKey,
		String code,
		String reason) implements DomainEvent {

	public static final String TYPE = "InventoryReservationFailed";


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
