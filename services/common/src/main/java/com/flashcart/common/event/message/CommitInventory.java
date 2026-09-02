package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Turn a hold into a sale: the units physically leave the warehouse.
 *
 * <p>Sent once payment has actually completed, never before. Committing early would take stock out
 * of the building for money that had not arrived.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitInventory(EventMetadata metadata,
		String reservationKey) implements DomainEvent {

	public static final String TYPE = "CommitInventory";


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
