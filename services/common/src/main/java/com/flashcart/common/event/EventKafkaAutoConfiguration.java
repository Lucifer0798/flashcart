package com.flashcart.common.event;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Gives every service a producer and an {@link EventPublisher}, configured the same way.
 *
 * <p>Auto-configuration rather than a shared {@code @Configuration} class, so a service opts in
 * simply by having Kafka on its classpath and the gateway — which does not — is unaffected.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
public class EventKafkaAutoConfiguration {

	/**
	 * A producer configured for a system that must not lose a message it has already acted on.
	 *
	 * <p>{@code acks=all} plus idempotence means a send is acknowledged only once every in-sync
	 * replica holds it, and a producer-level retry cannot silently duplicate or reorder it. The
	 * defaults are faster and would let an acknowledged publish vanish with a broker — which, for a
	 * message that says "this customer's money moved", is not a trade worth taking.
	 *
	 * <p>{@code max.block.ms} is set explicitly here rather than through YAML, where it has proved
	 * unreliable. Its default of 60s means an unreachable broker blocks the calling thread for a full
	 * minute per send, turning a Kafka hiccup into an outage in a service that was only trying to
	 * tell someone about something.
	 *
	 * <p>Type-info headers are off. They make the producer write its own class names into every
	 * message, which quietly couples every consumer to this service's package layout; each consumer
	 * declares the type it expects instead.
	 */
	@Bean
	@ConditionalOnMissingBean
	public ProducerFactory<String, Object> flashcartProducerFactory(KafkaProperties properties,
			KafkaConnectionDetails connectionDetails) {
		Map<String, Object> config = properties.buildProducerProperties();
		// buildProducerProperties reads the raw spring.kafka.* properties and knows nothing about
		// KafkaConnectionDetails, so anything that supplies the broker address that way — Testcontainers
		// service connections, Boot's Docker Compose support, a service binding — would be silently
		// ignored in favour of whatever application.yml happens to say. Spring Boot's own producer
		// auto-configuration applies the same override for the same reason.
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
				connectionDetails.getProducer().getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		// JacksonJsonSerializer, not the older JsonSerializer: Boot 4 ships Jackson 3, and the
		// legacy serializer is compiled against Jackson 2's com.fasterxml types.
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
		config.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, false);
		config.put(ProducerConfig.ACKS_CONFIG, "all");
		config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
		// These three are validated against each other by the client, and the check is
		// delivery.timeout.ms >= linger.ms + request.timeout.ms. Both request.timeout.ms and
		// delivery.timeout.ms default to 30s, so setting only the latter to 30s fails that check and
		// the producer refuses to construct — taking the whole service down at startup, but only
		// against a real broker, which is why it survived every test that mocked one.
		config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
		config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
		config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	@ConditionalOnMissingBean
	public KafkaTemplate<String, Object> flashcartKafkaTemplate(
			ProducerFactory<String, Object> producerFactory) {
		return new KafkaTemplate<>(producerFactory);
	}

	@Bean
	@ConditionalOnMissingBean
	public EventPublisher eventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
		return new KafkaEventPublisher(kafkaTemplate);
	}
}
