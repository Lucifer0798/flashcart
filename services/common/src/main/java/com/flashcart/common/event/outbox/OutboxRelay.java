package com.flashcart.common.event.outbox;

import java.util.List;
import java.util.UUID;

import com.flashcart.common.web.CorrelationId;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Moves committed outbox rows onto Kafka.
 *
 * <h2>Why the payload goes out as a string</h2>
 *
 * The row already holds the exact JSON the normal publisher would have produced, so the relay sends
 * those bytes verbatim through a string serializer. Deserialising it back into an object only to
 * re-serialise it would risk the two encodings differing — and consumers would then be reading a
 * subtly different wire format depending on which path a message happened to take.
 *
 * <h2>Ordering</h2>
 *
 * Rows are taken in insertion order and Kafka keys by aggregate id, so messages about one order stay
 * in sequence. {@code FOR UPDATE SKIP LOCKED} lets several instances relay at once without
 * duplicating work or queueing on the same rows — but note it also means two instances can interleave
 * <em>across</em> aggregates. That is fine: ordering is only ever promised within one.
 *
 * <h2>What happens on failure</h2>
 *
 * A row that cannot be published stays unpublished and is retried on the next tick, with its attempt
 * count incremented. Nothing is dropped. A row whose attempts keep climbing is a genuine operational
 * signal — it is logged loudly, because a message stuck in the outbox is a saga that has silently
 * stopped.
 */
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final String CLAIM = """
			select id, topic, message_key, event_id, event_type, correlation_id, payload::text, attempts
			  from outbox_messages
			 where published_at is null
			 order by created_at, id
			 limit ?
			   for update skip locked
			""";

	private static final String MARK_PUBLISHED =
			"update outbox_messages set published_at = now() where id = ?";

	private static final String RECORD_FAILURE =
			"update outbox_messages set attempts = attempts + 1, last_error = ? where id = ?";

	private final JdbcTemplate jdbc;
	private final KafkaTemplate<String, String> kafka;
	private final TransactionTemplate transactions;
	private final int batchSize;

	public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
			PlatformTransactionManager transactionManager, int batchSize) {
		this.jdbc = jdbc;
		this.kafka = kafka;
		// A TransactionTemplate rather than @Transactional: relayBatch is called from relay() on
		// this same object, and self-invocation does not pass through the proxy, so the annotation
		// would silently do nothing. That is not a cosmetic difference here — without a surrounding
		// transaction the FOR UPDATE SKIP LOCKED below takes its locks and drops them the instant the
		// query returns, and two instances would happily claim the same rows.
		this.transactions = new TransactionTemplate(transactionManager);
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${flashcart.outbox.relay.fixed-delay:PT1S}",
			initialDelayString = "${flashcart.outbox.relay.initial-delay:PT5S}")
	public void relay() {
		try {
			int published = relayBatch();
			if (published > 0) {
				log.debug("Relayed {} outbox message(s)", published);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws stops being rescheduled by some executors, and a relay
			// that quietly stops means every saga in the system halts with no error anywhere.
			log.error("Outbox relay failed; will retry on the next tick", ex);
		}
	}

	/** One batch, exposed so tests can drive it without waiting on a timer. */
	public int relayBatch() {
		return transactions.execute(status -> claimAndSend());
	}

	private int claimAndSend() {
		List<Pending> pending = jdbc.query(CLAIM,
				(rs, rowNum) -> new Pending(
						rs.getObject("id", UUID.class),
						rs.getString("topic"),
						rs.getString("message_key"),
						rs.getString("event_id"),
						rs.getString("event_type"),
						rs.getString("correlation_id"),
						rs.getString("payload"),
						rs.getInt("attempts")),
				batchSize);

		int published = 0;
		for (Pending message : pending) {
			if (send(message)) {
				jdbc.update(MARK_PUBLISHED, message.id());
				published++;
			}
		}
		return published;
	}

	private boolean send(Pending message) {
		ProducerRecord<String, String> record =
				new ProducerRecord<>(message.topic(), message.key(), message.payload());
		record.headers().add(new RecordHeader("eventType",
				message.eventType().getBytes(StandardCharsets.UTF_8)));
		record.headers().add(new RecordHeader("eventId",
				message.eventId().getBytes(StandardCharsets.UTF_8)));
		if (message.correlationId() != null) {
			record.headers().add(new RecordHeader(CorrelationId.HEADER,
					message.correlationId().getBytes(StandardCharsets.UTF_8)));
		}

		try {
			// Awaited deliberately, unlike the direct publisher: the row must only be marked
			// published once the broker has actually acknowledged it. Marking optimistically would
			// reintroduce exactly the loss the outbox exists to prevent.
			kafka.send(record).get();
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception ex) {
			jdbc.update(RECORD_FAILURE, ex.getMessage(), message.id());
			if (message.attempts() > 0 && message.attempts() % 10 == 0) {
				log.error("Outbox message {} has failed {} times; a saga is stalled behind it",
						message.eventId(), message.attempts(), ex);
			}
			return false;
		}
	}

	private record Pending(UUID id, String topic, String key, String eventId, String eventType,
			String correlationId, String payload, int attempts) {
	}
}
