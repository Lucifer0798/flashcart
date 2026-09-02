package com.flashcart.inventory.messaging;

import com.flashcart.common.event.ConsumerFactories;
import com.flashcart.common.event.message.CommitInventory;
import com.flashcart.common.event.message.ReleaseInventory;
import com.flashcart.common.event.message.ReserveInventory;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * One typed listener factory per command this service accepts.
 *
 * <p>Separate factories rather than one shared factory with a default type: a single factory breaks
 * the moment a service consumes a second message type, and breaks by deserialising into the wrong
 * class rather than by failing loudly.
 */
@Configuration
public class InventoryKafkaConfig {

	/** All three listeners share one group, so this service sees each command exactly once. */
	static final String GROUP = "flashcart-inventory";

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, ReserveInventory> reserveInventoryFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, ReserveInventory.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, ReleaseInventory> releaseInventoryFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, ReleaseInventory.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, CommitInventory> commitInventoryFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, CommitInventory.class, template);
	}
}
