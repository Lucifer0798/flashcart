package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A shopper waiting for a sold-out SKU has been told units came back. One per shopper told.
 *
 * <p>Published by inventory in the same transaction as the stock change that freed the units and the
 * claim that chose this shopper, so the three cannot disagree. Nothing in the platform consumes it
 * yet: it is the hook a notifier (email, push) would subscribe to. The aggregate is the waitlist
 * entry, so a consumer that dedupes by aggregate tells each place in the queue once. See ADR 0044.
 *
 * @param waitlistEntryId the place in the queue this notice is for
 * @param heldUntil       when the unit held for this shopper is released if they have not checked out
 *                        (ADR 0045); null if no unit could be held, which the notice still reports
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackInStock(EventMetadata metadata,
		String waitlistEntryId,
		String sku,
		String customerId,
		Instant notifiedAt,
		Instant heldUntil) implements DomainEvent {

	public static final String TYPE = "BackInStock";


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
