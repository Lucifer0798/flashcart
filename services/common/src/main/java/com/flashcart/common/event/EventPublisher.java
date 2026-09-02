package com.flashcart.common.event;

/**
 * Publishes a message to the bus.
 *
 * <p>An interface so services depend on "something that publishes" rather than on Kafka. That keeps
 * the domain code free of broker types, and it is what will let Phase 8 slip a transactional outbox
 * underneath without any caller changing: the outbox implementation writes to a table, and a relay
 * publishes from it.
 */
public interface EventPublisher {

	/**
	 * Send {@code message} to {@code topic}, keyed by its aggregate id.
	 *
	 * <p>Keying by aggregate is not optional. Kafka orders messages within a partition only, so two
	 * messages about the same order landing on different partitions can be consumed out of order —
	 * and an order service that sees {@code PaymentCompleted} before {@code InventoryReserved} has no
	 * legal transition to make of it.
	 */
	void publish(String topic, DomainEvent message);
}
