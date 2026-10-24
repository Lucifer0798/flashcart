package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A customer closed their account. See ADR 0053.
 *
 * <p>Published by the user service in the transaction that erased the account's identity, so it cannot
 * announce a closure that rolled back. By then the email, name, password and addresses are gone; what
 * each other service holds is keyed by an id that no longer leads to a person, and each decides for itself
 * what closing means for it. Order refuses new checkouts from the id; inventory gives up its waitlist
 * places. Payments, orders and parcels are kept, as the records they are.
 *
 * <p>Keyed by the customer id, the one thing it carries, so every message about one account stays in order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountClosed(EventMetadata metadata,
		String customerId) implements DomainEvent {

	public static final String TYPE = "AccountClosed";

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
