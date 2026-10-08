package com.flashcart.user.messaging;

import com.flashcart.common.event.ConsumerFactories;
import com.flashcart.common.event.message.BackInStock;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The one message this service consumes. A typed factory, as in every other service, so the event-type
 * filter discards the rest of {@code flashcart.inventory.events} instead of deserialising it into the
 * wrong record. See ADR 0046.
 */
@Configuration
public class UserKafkaConfig {

	static final String GROUP = "flashcart-user";

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, BackInStock> backInStockFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, BackInStock.class, template);
	}
}
