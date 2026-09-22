package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An order finished, the way it was supposed to.
 *
 * <p>The third of the lifecycle facts on the order topic, beside {@code OrderConfirmed} and
 * {@code OrderCancelled}. Nothing inside this platform subscribes to any of them — that topic is the
 * seam for whatever lives outside it, and this is the event a "your order arrived" notification would
 * be built on. Publishing the other two and not this one would leave the only happy ending missing
 * from the stream that exists to carry endings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDelivered(EventMetadata metadata,
		String orderNumber,
		String customerId) implements DomainEvent {

	public static final String TYPE = "OrderDelivered";


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
