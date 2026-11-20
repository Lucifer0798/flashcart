package com.flashcart.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.outbox.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox relay against a real broker and a real table, driven by hand. See ADR 0076.
 *
 * <p>Its own class, because InventoryKafkaIT needs the relay's timer running, and a timer publishing in the
 * background would let a test of "one call drains the queue" pass with the drain broken. Here the timer is
 * parked, the batch is ten, and every message is sent by the call under test.
 */
@SpringBootTest(properties = {
		"flashcart.outbox.relay.initial-delay=PT1H",
		"flashcart.outbox.relay.batch-size=10",
		"flashcart.outbox.relay.drain-budget=PT30S",
		// Nothing here consumes, and the topics are this test's own.
		"spring.kafka.listener.auto-startup=false",
		"flashcart.inventory.sweeper.enabled=false"
})
class OutboxRelayIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	/** The same image as InventoryKafkaIT, for the reason given there. */
	@ServiceConnection
	static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

	static {
		POSTGRES.start();
		KAFKA.start();
	}

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void emptyQueue() {
		jdbc.update("delete from outbox_messages");
	}

	@Test
	@DisplayName("one tick drains a backlog of several batches, and a key's messages arrive in the order they were queued")
	void drainsTheBacklogInOrder() {
		String topic = "relay-test-" + UUID.randomUUID();
		for (int n = 0; n < 35; n++) {
			queue(topic, "one-order", n, n);
		}

		// One tick. Before ADR 0076 this sent one batch of ten and left twenty-five for the next three seconds.
		relay.relay();

		assertThat(jdbc.queryForObject("select count(*) from outbox_messages where published_at is null", Integer.class))
				.isZero();
		assertThat(consume(topic, 35)).extracting(ConsumerRecord::value)
				.containsExactlyElementsOf(java.util.stream.IntStream.range(0, 35).mapToObj(OutboxRelayIT::payload).toList());
	}

	@Test
	@DisplayName("a message the broker refuses stays queued and counted, the rest of its batch is sent, and the drain stops")
	void aRefusedMessageEndsTheDrain() {
		String topic = "relay-test-" + UUID.randomUUID();
		// Fifteen good messages and, fifth in line so it lands in the first batch of ten, one the broker refuses.
		UUID refused = null;
		for (int position = 0; position < 16; position++) {
			if (position == 4) {
				// Not a legal topic name, so the producer refuses it: the outbox's job is to keep it and say so.
				refused = queue("not a legal topic!", "bad", 99, position);
			}
			else {
				queue(topic, "k-" + position, position, position);
			}
		}

		relay.relay();

		Map<String, Object> bad = jdbc.queryForMap("select published_at, attempts, last_error from outbox_messages where id = ?",
				refused);
		assertThat(bad.get("published_at")).isNull();
		assertThat(bad.get("attempts")).isEqualTo(1);
		assertThat((String) bad.get("last_error")).isNotBlank();
		// The first batch held the refused one and nine good ones, and came back short, so the tick stopped there:
		// the last six wait for the next tick, where a failing broker would get its second chance.
		assertThat(jdbc.queryForObject("select count(*) from outbox_messages where published_at is not null", Integer.class))
				.isEqualTo(9);
		assertThat(consume(topic, 9)).hasSize(9);
	}

	@Test
	@DisplayName("a message the broker rejects after it was sent stays queued: a row is marked only once the broker has it")
	void aBrokerRejectionIsNotMarkedSent() throws Exception {
		// Accepted by the producer, which allows a megabyte, and refused by the broker, which this topic tells to
		// take no more than a hundred bytes. So the send is in flight before it fails, which is the case marking
		// a row before the acknowledgement would get wrong. An illegal topic name is refused before that.
		String small = "relay-small-" + UUID.randomUUID();
		try (org.apache.kafka.clients.admin.Admin admin = org.apache.kafka.clients.admin.Admin.create(
				Map.of(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(small, 1, (short) 1)
					.configs(Map.of("max.message.bytes", "100")))).all().get();
		}
		String topic = "relay-test-" + UUID.randomUUID();
		queue(topic, "k-0", 0, 0);
		UUID rejected = UUID.randomUUID();
		jdbc.update("""
				insert into outbox_messages (id, topic, message_key, event_id, event_type, payload, created_at)
				values (?, ?, 'too-big', ?, 'RelayProbe', ?::jsonb, now() + make_interval(secs => 1 / 1000.0))""",
				rejected, small, UUID.randomUUID().toString(), "{\"pad\": \"" + "x".repeat(300) + "\"}");
		queue(topic, "k-2", 2, 2);

		relay.relay();

		Map<String, Object> row = jdbc.queryForMap("select published_at, attempts, last_error from outbox_messages where id = ?",
				rejected);
		assertThat(row.get("published_at")).isNull();
		assertThat(row.get("attempts")).isEqualTo(1);
		// The broker's reason, not Spring's "Failed to send" wrapped around it.
		assertThat((String) row.get("last_error")).startsWith("RecordTooLargeException");
		assertThat(consume(topic, 2)).hasSize(2);
	}

	private UUID queue(String topic, String key, int n, int position) {
		UUID id = UUID.randomUUID();
		// Spaced a millisecond apart by position, so created_at alone fixes the order the relay claims them in.
		jdbc.update("""
				insert into outbox_messages (id, topic, message_key, event_id, event_type, payload, created_at)
				values (?, ?, ?, ?, 'RelayProbe', ?::jsonb, now() + make_interval(secs => ? / 1000.0))""",
				id, topic, key, UUID.randomUUID().toString(), payload(n), position);
		return id;
	}

	private static String payload(int n) {
		return "{\"n\": " + n + "}";
	}

	private static List<ConsumerRecord<String, String>> consume(String topic, int expected) {
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
			consumer.subscribe(List.of(topic));
			List<ConsumerRecord<String, String>> records = new ArrayList<>();
			long deadline = System.currentTimeMillis() + 30_000;
			while (records.size() < expected && System.currentTimeMillis() < deadline) {
				consumer.poll(Duration.ofMillis(500)).forEach(records::add);
			}
			// Polled a little longer, so a duplicate would show up as one too many rather than be missed.
			consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
			return records;
		}
	}
}
