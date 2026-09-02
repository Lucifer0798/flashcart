package com.flashcart.common.event.message;

import java.time.Instant;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Hold stock for an order.
 *
 * <p>The command that replaced the synchronous call in Phase 4. {@code reservationKey} is the order
 * id, which is what makes inventory idempotent on redelivery — and redelivery is certain, so this
 * command being safe to apply twice is not a nicety but the thing that makes the whole flow work.
 *
 * @param flashSaleId set to have the sale allocation and per-customer cap enforced too
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveInventory(EventMetadata metadata,
		String reservationKey,
		String customerId,
		String flashSaleId,
		java.util.List<OrderLineMessage> lines) implements DomainEvent {

	public static final String TYPE = "ReserveInventory";


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
