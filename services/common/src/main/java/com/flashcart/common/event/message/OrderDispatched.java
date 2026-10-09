package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The order's parcel has left with the carrier: the moment cancelling stops being possible.
 *
 * <p>Shipping's {@code ShipmentDispatched} says the same thing about a shipment; this is the order's own
 * account of it, carrying the customer, so whatever tells the shopper -- the user service's email, since
 * ADR 0048 -- hears the order's story from the order. Published once, on entering {@code DISPATCHED},
 * whichever way the order got there.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDispatched(EventMetadata metadata,
		String orderNumber,
		String customerId,
		Instant dispatchedAt) implements DomainEvent {

	public static final String TYPE = "OrderDispatched";


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
