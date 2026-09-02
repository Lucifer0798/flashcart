package com.flashcart.common.event;

import com.flashcart.common.web.CorrelationId;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

/**
 * The Kafka implementation.
 *
 * <p>Two details worth stating.
 *
 * <p><strong>Send is fire-and-log, not fire-and-forget.</strong> The returned future is observed so
 * a failed publish is at least visible; it is not awaited, because blocking a request thread on the
 * broker is how a Kafka hiccup becomes an outage in a service that was only trying to tell someone
 * about something. Until Phase 8's outbox, a publish that fails after the database committed is a
 * genuine hole — that is precisely the hole the outbox exists to close, and it is logged loudly so
 * it is not mistaken for working.
 *
 * <p><strong>The correlation id also travels as a header</strong>, not only in the payload, so
 * infrastructure that never deserialises the body — a console, a bridge, a DLQ inspector — can still
 * tie a message back to the request that caused it.
 */
public class KafkaEventPublisher implements EventPublisher {

	private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

	private final KafkaTemplate<String, Object> kafka;

	public KafkaEventPublisher(KafkaTemplate<String, Object> kafka) {
		this.kafka = kafka;
	}

	@Override
	public void publish(String topic, DomainEvent message) {
		ProducerRecord<String, Object> record =
				new ProducerRecord<>(topic, message.aggregateId(), message);
		record.headers().add(new RecordHeader("eventType",
				message.eventType().getBytes(StandardCharsets.UTF_8)));
		record.headers().add(new RecordHeader("eventId",
				message.eventId().getBytes(StandardCharsets.UTF_8)));
		if (message.correlationId() != null) {
			record.headers().add(new RecordHeader(CorrelationId.HEADER,
					message.correlationId().getBytes(StandardCharsets.UTF_8)));
		}

		try {
			kafka.send(record).whenComplete((result, failure) -> {
				if (failure != null) {
					log.error("Failed to publish {} for {} to {}", message.eventType(),
							message.aggregateId(), topic, failure);
				}
				else {
					log.debug("Published {} for {} to {}", message.eventType(), message.aggregateId(),
							topic);
				}
			});
		}
		catch (RuntimeException ex) {
			// KafkaTemplate.send can throw synchronously when it cannot get cluster metadata in
			// time, so the failure does not always arrive on the future. Both paths need catching.
			log.error("Failed to publish {} for {} to {}", message.eventType(), message.aggregateId(),
					topic, ex);
		}
	}
}
