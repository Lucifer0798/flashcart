package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parcel is with the carrier.
 *
 * <p>The last transition shipping made without telling anyone. It matters because it is the moment a
 * cancellation stops being possible: until ADR 0033 the order service could not know it had passed,
 * so refusing a cancellation meant asking over the bus and waiting to be told what shipping had
 * already recorded.
 *
 * <p>Carries {@code dispatchedAt} from the warehouse for the reason {@code ShipmentDelivered} does:
 * two records of one event that disagree about when it happened are worse than one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentDispatched(EventMetadata metadata,
		String shipmentId,
		String orderNumber,
		String trackingNumber,
		Instant dispatchedAt) implements DomainEvent {

	public static final String TYPE = "ShipmentDispatched";


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
