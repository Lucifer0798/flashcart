package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A customer's data was downloaded from one service: one of the five files of ADR 0066. See ADR 0080.
 *
 * <p>Each service announces its own file on its own events topic, and the user service, which holds the
 * address, emails the owner. A stolen token could take the order, payment or parcel file without ever
 * touching the user service, so announcing only that one would leave the others unseen.
 *
 * <p>Keyed by the customer id, so every download by one customer lands on one partition per topic.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerDataExported(EventMetadata metadata,
		String customerId,
		String service) implements DomainEvent {

	public static final String TYPE = "CustomerDataExported";

	public static CustomerDataExported of(String service, String customerId) {
		return new CustomerDataExported(EventMetadata.of(TYPE, customerId), customerId, service);
	}

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
