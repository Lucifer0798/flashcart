package com.flashcart.inventory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.InventoryReservationFailed;
import com.flashcart.common.event.message.InventoryReserved;
import com.flashcart.common.event.message.OrderLineMessage;
import com.flashcart.common.event.message.ReleaseInventory;
import com.flashcart.common.event.message.ReserveInventory;
import com.flashcart.inventory.api.dto.CreateStockRequest;
import com.flashcart.inventory.api.dto.StockResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The one test that runs a real broker.
 *
 * <p>Every other test in this repo captures the bus rather than running it, which is right: they are
 * about what a service decides, and a real broker would make each assertion wait on a poll while
 * re-testing Kafka's plumbing over and over.
 *
 * <p>But that leaves a whole class of bug untested, and it is not a small one. Serializer
 * configuration, the Jackson 3 versus Jackson 2 split, type headers the producer writes and the
 * consumer refuses, a topic name that differs by one character between the two sides — none of it is
 * visible until a message actually crosses a broker. A suite that never does one round trip can be
 * entirely green against a platform where nothing talks to anything.
 *
 * <p>So this does exactly one thing: publishes a real command, lets the real listener consume it, and
 * waits for the real reply on a real topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class InventoryKafkaIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	/**
	 * {@code org.testcontainers.kafka.KafkaContainer}, not the one in
	 * {@code org.testcontainers.containers}. The latter is Confluent-only — it bakes in a
	 * {@code cp-kafka} startup script and fails on a native Apache image with
	 * {@code zookeeper-server-start: command not found}, which is a confusing way to learn you picked
	 * the wrong class of the same name.
	 */
	@ServiceConnection
	// 4.x, not the 3.9.0 the compose stack runs. Testcontainers' KafkaContainer injects a starter
	// script that the 3.9.0 image's entrypoint mangles into env-var assignments, and the broker exits
	// 1 before it ever logs anything useful. The compose stack pins 3.9.0 because it configures the
	// broker itself and never hits that path.
	static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

	static {
		// Started here rather than through @Container so their lifetime is the JVM's: JUnit would
		// stop them when this class finishes while Spring's context cache kept the pools alive for
		// the next class, and every test there would hang.
		POSTGRES.start();
		KAFKA.start();
	}

	/** Captures what inventory publishes back, off the real topic. */
	@Component
	static class ReplyCollector {

		private final List<Object> replies = java.util.Collections.synchronizedList(
				new java.util.ArrayList<>());

		@KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "inventory-kafka-it",
				containerFactory = "reserveInventoryFactory")
		void collect(ConsumerRecord<String, ?> record) {
			replies.add(record.value());
		}

		List<Object> replies() {
			return List.copyOf(replies);
		}
	}

	@Autowired
	private EventPublisher publisher;

	@Autowired
	private TestRestTemplate rest;

	private String stock(int quantity) {
		String sku = ("KAFKA-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
		ResponseEntity<StockResponse> response = rest.postForEntity("/api/v1/inventory/stock",
				new CreateStockRequest(sku, quantity, "kafka round-trip test"), StockResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return sku;
	}

	@Test
	@DisplayName("a command published to a real broker is consumed, acted on, and answered")
	void realRoundTrip() {
		String sku = stock(5);
		UUID orderId = UUID.randomUUID();

		publisher.publish(Topics.INVENTORY_COMMANDS, new ReserveInventory(
				EventMetadata.of(ReserveInventory.TYPE, orderId),
				orderId.toString(), "cust-kafka", null,
				List.of(new OrderLineMessage(sku, 2))));

		// The stock actually moved, which means the message serialised, crossed the broker,
		// deserialised into the right type, and the listener ran.
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			StockResponse position = rest.getForObject("/api/v1/inventory/stock/" + sku,
					StockResponse.class);
			assertThat(position.reserved()).isEqualTo(2);
			assertThat(position.available()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("a refusal comes back as an event, not as a dead-lettered exception")
	void refusalIsPublishedNotThrown() {
		String sku = stock(1);
		UUID orderId = UUID.randomUUID();

		publisher.publish(Topics.INVENTORY_COMMANDS, new ReserveInventory(
				EventMetadata.of(ReserveInventory.TYPE, orderId),
				orderId.toString(), "cust-kafka", null,
				List.of(new OrderLineMessage(sku, 99))));

		// Sold out is an ordinary outcome. If it threw, the retries would burn and the message would
		// dead-letter — stalling the partition behind every other order sharing it.
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			StockResponse position = rest.getForObject("/api/v1/inventory/stock/" + sku,
					StockResponse.class);
			assertThat(position.reserved()).isZero();
		});
	}

	@Test
	@DisplayName("a release command crosses the broker and returns the units")
	void releaseRoundTrip() {
		String sku = stock(4);
		UUID orderId = UUID.randomUUID();

		publisher.publish(Topics.INVENTORY_COMMANDS, new ReserveInventory(
				EventMetadata.of(ReserveInventory.TYPE, orderId),
				orderId.toString(), "cust-kafka", null,
				List.of(new OrderLineMessage(sku, 3))));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(rest.getForObject("/api/v1/inventory/stock/" + sku, StockResponse.class)
						.reserved()).isEqualTo(3));

		publisher.publish(Topics.INVENTORY_COMMANDS, new ReleaseInventory(
				EventMetadata.of(ReleaseInventory.TYPE, orderId),
				orderId.toString(), "test release"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			StockResponse position = rest.getForObject("/api/v1/inventory/stock/" + sku,
					StockResponse.class);
			assertThat(position.reserved()).isZero();
			assertThat(position.available()).isEqualTo(4);
		});
	}

	@Test
	@DisplayName("a redelivered command holds the units once")
	void duplicateCommandHoldsOnce() {
		String sku = stock(10);
		UUID orderId = UUID.randomUUID();

		ReserveInventory command = new ReserveInventory(
				EventMetadata.of(ReserveInventory.TYPE, orderId),
				orderId.toString(), "cust-kafka", null,
				List.of(new OrderLineMessage(sku, 2)));

		publisher.publish(Topics.INVENTORY_COMMANDS, command);
		publisher.publish(Topics.INVENTORY_COMMANDS, command);

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(rest.getForObject("/api/v1/inventory/stock/" + sku, StockResponse.class)
						.reserved()).isEqualTo(2));

		// Held for long enough that a second hold would have shown up by now.
		await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				assertThat(rest.getForObject("/api/v1/inventory/stock/" + sku, StockResponse.class)
						.reserved()).isEqualTo(2));
	}
}
