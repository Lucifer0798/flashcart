package com.flashcart.payment.messaging;

import com.flashcart.common.event.ConsumerFactories;
import com.flashcart.common.event.message.RequestPayment;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class PaymentKafkaConfig {

	static final String GROUP = "flashcart-payment";

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, RequestPayment> requestPaymentFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails,
			KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, connectionDetails, GROUP, RequestPayment.class, template);
	}
}
