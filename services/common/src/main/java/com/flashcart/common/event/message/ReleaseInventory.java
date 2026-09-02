package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Give a hold back.
 *
 * <p>The compensating command. Sent when payment declines, when a customer cancels, or when the
 * order service decides a hold is no longer wanted. Idempotent on inventory's side, and a no-op
 * against a hold that already lapsed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseInventory(EventMetadata metadata,
		String reservationKey,
		String reason) implements DomainEvent {

	public static final String TYPE = "ReleaseInventory";


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
