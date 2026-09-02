package com.flashcart.common.event;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.common.web.CorrelationId;

/**
 * The four envelope fields, filled in one place.
 *
 * <p>Every message record embeds one of these rather than repeating the metadata in its own
 * component list. Without it, each new message type is another chance to forget the correlation id
 * or to mint the event id from the wrong source — and a message with a non-unique event id silently
 * defeats every consumer's idempotency at once.
 */
public record EventMetadata(
		String eventId,
		String eventType,
		String aggregateId,
		Instant occurredAt,
		String correlationId) {

	/**
	 * Stamps a new message about {@code aggregateId}, taking the correlation id from the request or
	 * job currently in flight.
	 */
	public static EventMetadata of(String eventType, String aggregateId) {
		return new EventMetadata(UUID.randomUUID().toString(), eventType, aggregateId, Instant.now(),
				CorrelationId.current());
	}

	public static EventMetadata of(String eventType, UUID aggregateId) {
		return of(eventType, aggregateId.toString());
	}
}
