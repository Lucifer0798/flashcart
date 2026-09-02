package com.flashcart.common.event;

import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares every topic, so none of them depends on auto-creation.
 *
 * <p>Auto-creation looks convenient and is a bad idea twice over. Operationally, a topic conjured by
 * the first producer to mention it gets the broker's default partition count and replication factor,
 * which are almost never what you want — and partition count cannot be reduced afterwards. And a
 * typo in a topic name silently creates a brand-new topic rather than failing, so a producer and its
 * consumer can end up on two different topics that both exist and neither of which is wrong.
 *
 * <p>It is also a real startup hazard: the first publish to a not-yet-created topic blocks waiting
 * for metadata, and with a short {@code max.block.ms} it simply times out. That is not hypothetical —
 * it is what the real-broker integration test hit before this class existed.
 *
 * <p>Three partitions each, because messages are keyed by order id: partitions are the unit of
 * parallelism for consumers, and ordering is guaranteed only within one. Replication is 1 because
 * the local broker is a single node; a real cluster would want 3, which is why it is a property
 * rather than a constant.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaAdmin.class)
@ConditionalOnProperty(name = "flashcart.kafka.declare-topics", havingValue = "true",
		matchIfMissing = true)
public class EventTopicsConfiguration {

	private static final int PARTITIONS = 3;
	private static final short REPLICAS = 1;

	private static NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
	}

	@Bean
	public KafkaAdmin.NewTopics flashcartTopics() {
		return new KafkaAdmin.NewTopics(List.of(
				topic(Topics.ORDER_EVENTS),
				topic(Topics.INVENTORY_COMMANDS),
				topic(Topics.INVENTORY_EVENTS),
				topic(Topics.PAYMENT_COMMANDS),
				topic(Topics.PAYMENT_EVENTS),
				topic(Topics.SHIPPING_COMMANDS),
				topic(Topics.SHIPPING_EVENTS),
				topic(Topics.CATALOG_EVENTS),
				// The dead-letter topic is declared alongside the rest rather than created on first
				// use — the moment it is first needed is the worst moment to discover it is missing.
				topic(Topics.DEAD_LETTER)).toArray(NewTopic[]::new));
	}
}
