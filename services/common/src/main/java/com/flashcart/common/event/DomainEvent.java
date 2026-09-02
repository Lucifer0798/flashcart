package com.flashcart.common.event;

import java.time.Instant;

/**
 * The envelope contract every FlashCart message honours, command or event alike.
 *
 * <p>Four fields carry the weight, and each exists because of something that would otherwise go
 * wrong:
 *
 * <ul>
 *   <li>{@link #eventId()} is what makes a consumer idempotent. At-least-once delivery is a
 *       certainty, not a risk — a rebalance alone will redeliver — so a consumer that cannot
 *       recognise a message it has already applied is simply broken.</li>
 *   <li>{@link #aggregateId()} is the partition key. Kafka orders messages <em>within a partition
 *       only</em>, so every message about one order must key on that order or the order service will
 *       see its own saga out of sequence.</li>
 *   <li>{@link #occurredAt()} is when the fact happened, not when it was published. With Phase 8's
 *       outbox those can differ by a lot, and the fact's own time is the one that means something.</li>
 *   <li>{@link #correlationId()} carries the originating request across the async hop. It is the
 *       only thing that keeps a checkout traceable once it stops being a single HTTP call.</li>
 * </ul>
 *
 * <p>Implementations are plain records, one per message type, with the metadata inline. That is more
 * verbose than a generic {@code Envelope<T>} wrapper and deliberately so: it keeps the wire format
 * flat and free of polymorphic type metadata, which is the thing most likely to break a consumer
 * that was compiled against a slightly different version of the contract.
 */
public interface DomainEvent {

	/** Globally unique id for this exact message. The basis of consumer idempotency. */
	String eventId();

	/** Discriminator, e.g. {@code OrderCreated}. Lets one topic carry related message types. */
	String eventType();

	/** Id of the aggregate this message concerns. Used as the Kafka partition key. */
	String aggregateId();

	/** When the fact happened — not when it was published. */
	Instant occurredAt();

	/** The originating request's correlation id, propagated from {@code X-Correlation-Id}. */
	String correlationId();
}
