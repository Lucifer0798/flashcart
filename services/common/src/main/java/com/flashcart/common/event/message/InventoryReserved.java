package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stock is held, and here is when the hold lapses.
 *
 * <p>{@code expiresAt} is mirrored onto the order so its reconciler can find a lapsed hold without
 * waiting to be told about it — belt and braces against a lost expiry event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReserved(EventMetadata metadata,
		String reservationKey,
		Instant expiresAt) implements DomainEvent {

	public static final String TYPE = "InventoryReserved";


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
