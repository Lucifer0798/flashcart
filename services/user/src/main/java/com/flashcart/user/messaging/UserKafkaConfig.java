package com.flashcart.user.messaging;

import com.flashcart.common.event.ConsumerFactories;
import com.flashcart.common.event.message.BackInStock;
import com.flashcart.common.event.message.OrderCancelled;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.common.event.message.OrderDelivered;
import com.flashcart.common.event.message.OrderDispatched;
import com.flashcart.common.event.message.PaymentRefunded;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * One typed factory per message this service turns into an email, as in every other service: the
 * event-type filter discards the rest of each topic instead of deserialising it into the wrong record.
 * Each listener also has its own consumer group -- two listeners sharing one would split the topic's
 * partitions between them and each silently skip the other's messages. See ADRs 0046 and 0048.
 */
@Configuration
public class UserKafkaConfig {

	static final String GROUP = "flashcart-user";

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, BackInStock> backInStockFactory(KafkaProperties properties,
			KafkaConnectionDetails details, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, details, GROUP, BackInStock.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderConfirmed> orderConfirmedFactory(KafkaProperties properties,
			KafkaConnectionDetails details, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, details, GROUP, OrderConfirmed.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderDispatched> orderDispatchedFactory(KafkaProperties properties,
			KafkaConnectionDetails details, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, details, GROUP, OrderDispatched.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderDelivered> orderDeliveredFactory(KafkaProperties properties,
			KafkaConnectionDetails details, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, details, GROUP, OrderDelivered.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderCancelled> orderCancelledFactory(KafkaProperties properties,
			KafkaConnectionDetails details, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, details, GROUP, OrderCancelled.class, template);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, PaymentRefunded> paymentRefundedFactory(KafkaProperties properties,
			KafkaConnectionDetails details, KafkaTemplate<String, Object> template) {
		return ConsumerFactories.listenerFactory(properties, details, GROUP, PaymentRefunded.class, template);
	}
}
