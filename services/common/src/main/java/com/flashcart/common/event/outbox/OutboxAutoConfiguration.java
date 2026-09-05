package com.flashcart.common.event.outbox;

import java.util.Map;

import com.flashcart.common.event.EventPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
			ObjectProvider<MeterRegistry> registry,
			@org.springframework.beans.factory.annotation.Value(
					"${flashcart.outbox.relay.batch-size:100}") int batchSize) {
		return new OutboxRelay(jdbc, outboxKafkaTemplate, transactionManager, meterRegistry(registry),
				batchSize);
	}

	/**
	 * The outbox gauges, which are the only thing that makes a stalled relay visible.
	 *
	 * <p>Registered next to the relay rather than in a separate observability module, because the
	 * two are the same fact: the relay is allowed to fail silently precisely so it keeps running,
	 * and these are what turn that silence into a number.
	 */
	/**
	 * Keeps the two Phase 8 tables from growing for ever.
	 *
	 * <p>Not gated on {@code flashcart.outbox.enabled} for the same reason the dedup beans are not:
	 * a service that publishes directly still consumes idempotently, so {@code processed_events}
	 * still fills up and still needs pruning.
	 */
	@Bean
	public OutboxRetention outboxRetention(JdbcTemplate jdbc,
			@org.springframework.beans.factory.annotation.Value(
					"${flashcart.outbox.retention.published:P7D}") java.time.Duration published,
			@org.springframework.beans.factory.annotation.Value(
					"${flashcart.outbox.retention.processed:P7D}") java.time.Duration processed,
			@org.springframework.beans.factory.annotation.Value(
					"${flashcart.outbox.retention.batch-size:5000}") int batchSize) {
		return new OutboxRetention(jdbc, published, processed, batchSize);
	}

	@Bean
	@ConditionalOnProperty(name = "flashcart.outbox.enabled", havingValue = "true", matchIfMissing = true)
	public OutboxMetrics outboxMetrics(JdbcTemplate jdbc, ObjectProvider<MeterRegistry> registry) {
		return new OutboxMetrics(jdbc, meterRegistry(registry));
	}

	/**
	 * Falls back to a throwaway registry when the service has no actuator.
	 *
	 * <p>Metrics are optional here in the same way the web and Kafka pieces are: a service without a
	 * registry still gets a working outbox, it just is not instrumented. Recording into a discarded
	 * registry is cheaper than threading null checks through the relay's hot path.
	 */
	private static MeterRegistry meterRegistry(ObjectProvider<MeterRegistry> registry) {
		return registry.getIfAvailable(SimpleMeterRegistry::new);
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
