package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Put sold units back on the shelf.
 *
 * <p>Sent when shipping confirms a paid order's consignment was stopped before it left, which is the
 * one moment the platform knows for certain that committed units are still in the warehouse. Not sent
 * on the customer's request: a parcel that turns out to have been dispatched would then be counted
 * twice, once in the van and once on the shelf.
 *
 * <p>The inverse of {@link CommitInventory}, not of {@link ReleaseInventory}. A release gives back a
 * hold that never became a sale; this gives back a sale. Idempotent on inventory's side.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnInventory(EventMetadata metadata,
		String reservationKey,
		String reason) implements DomainEvent {

	public static final String TYPE = "ReturnInventory";


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
