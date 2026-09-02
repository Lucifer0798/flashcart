package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A hold ran out of time and its units were reclaimed.
 *
 * <p>Published by inventory's sweeper. This is the event that lets the order service move its own
 * state machine to {@code RESERVATION_EXPIRED} without polling for something that already happened.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationExpired(EventMetadata metadata,
		String reservationKey) implements DomainEvent {

	public static final String TYPE = "ReservationExpired";


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
