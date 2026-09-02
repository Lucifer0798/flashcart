package com.flashcart.common.event;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Builds a listener container factory for one message type, the same way in every service.
 *
 * <p>A helper rather than an auto-configuration because the interesting part — which type this
 * listener expects — is necessarily per-listener. What is shared is everything around it, and
 * getting any of it wrong is a class of bug that only shows up in production.
 *
 * <h2>One typed factory per message type</h2>
 *
 * Each listener gets a deserializer bound to exactly one class. The alternative, a single factory
 * with {@code spring.json.value.default.type}, breaks the moment a service consumes a second message
 * type — and breaks by silently deserialising into the wrong class rather than by failing.
 *
 * <p>Note the deserializer is constructed and handed over as an <em>instance</em>, and the
 * {@code key/value.deserializer} entries are stripped from the property map first. Supplying both an
 * instance and the property throws {@code IllegalStateException: JsonDeserializer must be configured
 * with property setters, or via configuration properties; not both} — and only when a real listener
 * container starts, so it is invisible to any test that does not start one.
 *
 * <h2>What happens when a listener throws</h2>
 *
 * Retries with exponential backoff, then the message goes to {@link Topics#DEAD_LETTER}. Without a
 * dead-letter destination the default behaviour is to retry the same poisoned message forever, which
 * stops the partition dead: one malformed message and that order — and every order sharing its
 * partition — stops moving, silently.
 */
public final class ConsumerFactories {

	private ConsumerFactories() {
	}

	/**
	 * @param connectionDetails where the broker actually is, which may differ from application.yml
	 * @param groupId  the consumer group. Distinct per service, so every service sees every message
	 *                 on a topic it subscribes to rather than competing for them.
	 * @param type     the class this listener deserialises into
	 * @param template the producer used to write dead-lettered messages
	 */
	public static <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(
			KafkaProperties properties, KafkaConnectionDetails connectionDetails, String groupId,
			Class<T> type, KafkaTemplate<String, Object> template) {

		Map<String, Object> config = properties.buildConsumerProperties();
		// Same override as the producer: buildConsumerProperties reads the raw spring.kafka.* values
		// and ignores KafkaConnectionDetails, so a Testcontainers service connection or Boot's Docker
		// Compose support would be silently overridden by whatever application.yml says.
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				connectionDetails.getConsumer().getBootstrapServers());
		// Removed before the instance is supplied — see the class comment for why both together fail.
		config.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
		config.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		// Read from the beginning for a brand-new group. On a first deploy — or a fresh compose
		// stack — `latest` would silently skip everything published before the listener attached.
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		// Manual acknowledgement below means an offset is committed only after the handler returned.
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		JacksonJsonDeserializer<T> valueDeserializer = new JacksonJsonDeserializer<>(type);
		// The producer does not write type headers, so the deserializer must not look for them.
		valueDeserializer.setUseTypeHeaders(false);

		ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(config,
				new StringDeserializer(), valueDeserializer);

		ConcurrentKafkaListenerContainerFactory<String, T> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.setCommonErrorHandler(deadLetterErrorHandler(template));
		factory.setRecordFilterStrategy(onlyMessagesOfType(type));
		// Filtered records are acknowledged rather than left uncommitted; without this the offset
		// never advances past a message this listener does not want, and it is redelivered forever.
		factory.setAckDiscarded(true);
		return factory;
	}

	/**
	 * Drops messages that are not of the type this listener expects.
	 *
	 * <p><strong>Without this, a topic carrying more than one message type is actively dangerous.</strong>
	 * Several listeners subscribe to the same topic under different consumer groups, so every group
	 * receives every message — and a typed deserializer with {@code ignoreUnknown} will cheerfully
	 * turn a {@code ReserveInventory} into a {@code ReleaseInventory}, because they share a
	 * {@code reservationKey} field and the rest is silently discarded.
	 *
	 * <p>That is not a hypothetical. It happened: the release listener consumed a reserve command and
	 * released the hold the reserve listener had just taken, so stock appeared to reserve and then
	 * immediately un-reserve. Nothing threw, nothing was dead-lettered, and no test that mocked the
	 * broker could see it — only a real round trip surfaced it.
	 *
	 * <p>The filter reads the {@code eventType} header that {@link KafkaEventPublisher} writes, which
	 * is exactly what that header is for.
	 */
	private static <T> RecordFilterStrategy<String, T> onlyMessagesOfType(Class<T> type) {
		String expected = type.getSimpleName();
		return record -> {
			Header header = record.headers().lastHeader("eventType");
			if (header == null) {
				// No header means the message did not come from our publisher. Refusing it is safer
				// than guessing at its type.
				return true;
			}
			return !expected.equals(new String(header.value(), StandardCharsets.UTF_8));
		};
	}

	/**
	 * Retry a handful of times with growing gaps, then dead-letter.
	 *
	 * <p>The backoff matters: a downstream that is briefly unavailable recovers within a few
	 * seconds, and retrying instantly just burns through the attempts before it can. Capped so a
	 * genuinely broken message reaches the DLQ in seconds rather than minutes.
	 */
	private static DefaultErrorHandler deadLetterErrorHandler(KafkaTemplate<String, Object> template) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
				// Everything lands on one dead-letter topic, partition unset so Kafka picks. The
				// original topic, partition and offset travel in headers the recoverer adds.
				(record, exception) -> new org.apache.kafka.common.TopicPartition(Topics.DEAD_LETTER, -1));

		ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
		backOff.setMaxAttempts(4);
		backOff.setMaxInterval(4_000L);

		return new DefaultErrorHandler(recoverer, backOff);
	}
}
