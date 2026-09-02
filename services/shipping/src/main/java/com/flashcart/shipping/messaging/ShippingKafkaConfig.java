package com.flashcart.shipping.messaging;

import com.flashcart.common.event.ConsumerFactories;
import com.flashcart.common.event.message.CreateShipment;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class ShippingKafkaConfig {

	static final String GROUP = "flashcart-shipping";

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, CreateShipment> createShipmentFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, CreateShipment.class, template);
	}
}
