package com.flashcart.common.event;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener factories every service builds. See ADR 0070.
 *
 * <p>Nothing here connects to anything: building a factory and a container is configuration, and the broker
 * is only dialled when a container starts.
 */
class ConsumerFactoriesTest {

	private static final KafkaConnectionDetails NOWHERE = () -> List.of("localhost:1");

	@Test
	@DisplayName("spring.kafka.listener.auto-startup=false keeps the listener off the broker")
	void autoStartupFalseIsObeyed() {
		KafkaProperties properties = new KafkaProperties();
		properties.getListener().setAutoStartup(false);

		assertThat(containerFrom(properties).isAutoStartup()).isFalse();
	}

	@Test
	@DisplayName("left alone, a listener starts with the application, as production needs")
	void listenersStartByDefault() {
		assertThat(containerFrom(new KafkaProperties()).isAutoStartup()).isTrue();
	}

	private static org.springframework.kafka.listener.ConcurrentMessageListenerContainer<String, String> containerFrom(
			KafkaProperties properties) {
		KafkaTemplate<String, Object> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of()));
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
				ConsumerFactories.listenerFactory(properties, NOWHERE, "a-group", String.class, template);
		return factory.createContainer("a-topic");
	}
}
