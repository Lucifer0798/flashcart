package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Pick, pack and hand the order to a carrier. Sent once payment has settled. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateShipment(EventMetadata metadata,
		String orderNumber,
		String customerId,
		java.util.List<OrderLineMessage> lines) implements DomainEvent {

	public static final String TYPE = "CreateShipment";


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
