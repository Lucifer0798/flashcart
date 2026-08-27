package com.flashcart.common.event;

import java.time.Instant;

/**
 * The envelope contract every FlashCart event honours.
 *
 * <p>Phase 1 declares the shape; Phase 5 wires the producers and consumers and Phase 8 backs it with
 * a transactional outbox. Three fields carry the weight:
 *
 * <ul>
 *   <li>{@link #eventId()} is what makes a consumer idempotent. At-least-once delivery is a
 *       certainty, not a risk, so every consumer records the ids it has already applied.</li>
 *   <li>{@link #aggregateId()} is the partition key. Ordering in Kafka holds within a partition
 *       only, so all events for one order must key on that order to arrive in order.</li>
 *   <li>{@link #correlationId()} carries the caller's request id across the async hop, which is the
 *       only way a checkout stays traceable once it leaves HTTP.</li>
 * </ul>
 */
public interface DomainEvent {

	/** Globally unique id for this exact event instance. The basis of consumer idempotency. */
	String eventId();

	/** Discriminator, e.g. {@code OrderCreated}. Lets one topic carry related event types. */
	String eventType();

	/** Id of the aggregate this event is about. Used as the Kafka partition key. */
	String aggregateId();

	/** When the fact happened — not when it was published, which may be much later via the outbox. */
	Instant occurredAt();

	/** The originating request's correlation id, propagated from {@code X-Correlation-Id}. */
	String correlationId();
}
