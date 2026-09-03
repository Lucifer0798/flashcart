package com.flashcart.common.event.outbox;

import java.util.Map;

import com.flashcart.common.event.EventPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.ObjectMapper;

/**
 * Puts the outbox in front of Kafka for any service that has both a database and a broker.
 *
 * <p>The {@link OutboxEventPublisher} is {@link Primary}, so every existing caller of
 * {@code EventPublisher} starts writing to the outbox with no change to a single line of domain
 * code. That was the point of making {@code EventPublisher} an interface back in Phase 5 — the
 * comment there said an outbox would slip underneath it, and this is that.
 *
 * <p>{@code flashcart.outbox.enabled=false} switches off only the publishing half — the queue and
 * the relay. It deliberately leaves {@link ProcessedEvents} and {@link IdempotentHandler} in place,
 * because consumer-side deduplication is a separate concern that a service still needs whether or
 * not its own outbound messages go through an outbox. Tests that assert on what this service decides
 * substitute their own publisher and turn the queue off; they still consume idempotently.
 */
@AutoConfiguration
@ConditionalOnClass({ JdbcTemplate.class, KafkaTemplate.class })
@EnableScheduling
public class OutboxAutoConfiguration {

	/**
	 * Primary, so it displaces the direct Kafka publisher for every injection point.
	 *
	 * <p>The direct one stays in the context deliberately: {@link OutboxRelay} is what actually talks
	 * to the broker, and keeping both makes the split between "queue it" and "send it" explicit
	 * rather than hidden.
	 */
	@Bean
	@Primary
	@ConditionalOnProperty(name = "flashcart.outbox.enabled", havingValue = "true", matchIfMissing = true)
	public EventPublisher outboxEventPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		return new OutboxEventPublisher(jdbc, objectMapper);
	}

	/**
	 * A string-valued producer used only by the relay.
	 *
	 * <p>Separate from the platform's object-valued producer because the relay already holds the
	 * exact JSON that was serialised at queue time and must send those bytes unchanged — round
	 * tripping them through an object would risk the two paths producing subtly different wire
	 * formats.
	 */
	@Bean
	@ConditionalOnProperty(name = "flashcart.outbox.enabled", havingValue = "true", matchIfMissing = true)
	public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties properties,
			KafkaConnectionDetails connectionDetails) {
		Map<String, Object> config = properties.buildProducerProperties();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
				connectionDetails.getProducer().getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.ACKS_CONFIG, "all");
		config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
		// delivery.timeout.ms must be at least linger.ms + request.timeout.ms, and both of those
		// default to values that make a bare 30s here fail the client's own validation.
		config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
		config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
		config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

		ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(config);
		return new KafkaTemplate<>(factory);
	}

	@Bean
	@ConditionalOnProperty(name = "flashcart.outbox.enabled", havingValue = "true", matchIfMissing = true)
	public OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> outboxKafkaTemplate,
			org.springframework.transaction.PlatformTransactionManager transactionManager,
			@org.springframework.beans.factory.annotation.Value(
					"${flashcart.outbox.relay.batch-size:100}") int batchSize) {
		return new OutboxRelay(jdbc, outboxKafkaTemplate, transactionManager, batchSize);
	}

	@Bean
	public ProcessedEvents processedEvents(JdbcTemplate jdbc) {
		return new ProcessedEvents(jdbc);
	}

	@Bean
	public IdempotentHandler idempotentHandler(ProcessedEvents processedEvents,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		return new IdempotentHandler(processedEvents, transactionManager);
	}
}
