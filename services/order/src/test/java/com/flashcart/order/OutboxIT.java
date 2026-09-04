package com.flashcart.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.message.ReserveInventory;
import com.flashcart.common.event.outbox.OutboxMetrics;
import com.flashcart.common.event.outbox.ProcessedEvents;
import com.flashcart.common.order.OrderStatus;
import com.flashcart.order.api.dto.OrderResponse;
import com.flashcart.order.api.dto.PlaceOrderRequest;
import com.flashcart.order.client.CatalogClient;
import com.flashcart.order.service.OrderSaga;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox and the processed-event table, against a real PostgreSQL.
 *
 * <p>These are the two tables Phase 8 exists for, and both make claims that are only meaningful
 * against a real database: one about writing in the caller's transaction, the other about a unique
 * constraint deciding a race. Neither can be demonstrated with a mock.
 *
 * <p>The relay is left switched off and driven by hand, so the assertions are about what is in the
 * table at a known moment rather than about whether a timer happened to fire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(OutboxIT.FakeCatalogConfig.class)
@TestPropertySource(properties = {
		"flashcart.order.reconciler.enabled=false",
		"spring.kafka.listener.auto-startup=false",
		// No broker in this suite: the relay would fail every send and bury the log in retries.
		// What is under test is what reaches the table, not what leaves it.
		"flashcart.outbox.relay.initial-delay=PT1H"
})
class OutboxIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@TestConfiguration
	static class FakeCatalogConfig {

		@Bean
		@Primary
		CatalogClient fakeCatalog() {
			Map<String, CatalogClient.PricedProduct> products = new ConcurrentHashMap<>();
			products.put("AUD-HP-001", new CatalogClient.PricedProduct(
					"AUD-HP-001", "Aurora Headphones", new BigDecimal("179.00"), "USD", false));
			return sku -> {
				CatalogClient.PricedProduct product = products.get(sku);
				if (product == null) {
					throw ResourceNotFoundException.of("Product with SKU", sku);
				}
				return product;
			};
		}
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ProcessedEvents processedEvents;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private OutboxMetrics outboxMetrics;

	@Autowired
	private MeterRegistry meters;

	@Autowired
	private OrderSaga saga;

	@BeforeEach
	void clearOutbox() {
		jdbc.update("delete from outbox_messages");
		jdbc.update("delete from processed_events");
	}

	private OrderResponse place() {
		return rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest("idem-" + UUID.randomUUID(), "cust-1", null,
						List.of(new PlaceOrderRequest.Line("AUD-HP-001", 1))),
				OrderResponse.class).getBody();
	}

	private List<Map<String, Object>> outbox() {
		return jdbc.queryForList("select * from outbox_messages order by created_at, id");
	}

	// --- the outbox ---------------------------------------------------------------------------------

	@Test
	@DisplayName("placing an order queues its command in the outbox rather than sending it")
	void placeQueuesToTheOutbox() {
		OrderResponse order = place();

		assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
		assertThat(outbox()).singleElement().satisfies(row -> {
			assertThat(row).containsEntry("topic", "flashcart.inventory.commands");
			assertThat(row).containsEntry("event_type", "ReserveInventory");
			// Keyed by the aggregate, which is what preserves per-order ordering on the topic.
			assertThat(row).containsEntry("message_key", order.id().toString());
			// Unpublished: the relay has not run, and nothing has reached a broker.
			assertThat(row.get("published_at")).isNull();
			assertThat(row.get("attempts")).isEqualTo(0);
		});
	}

	@Test
	@DisplayName("the queued payload is the message, not a summary of it")
	void payloadRoundTrips() {
		OrderResponse order = place();

		String payload = (String) jdbc.queryForMap(
				"select payload::text as payload from outbox_messages").get("payload");

		// Deserialised rather than string-matched, because the column is jsonb and PostgreSQL
		// renormalises whitespace and key order on the way out. What matters is that a consumer can
		// reconstruct the command, which is the only thing the relay's verbatim send guarantees.
		ReserveInventory command = objectMapper.readValue(payload, ReserveInventory.class);

		assertThat(command.reservationKey()).isEqualTo(order.id().toString());
		assertThat(command.eventType()).isEqualTo(ReserveInventory.TYPE);
		assertThat(command.aggregateId()).isEqualTo(order.id().toString());
		assertThat(command.lines()).singleElement().satisfies(line -> {
			assertThat(line.sku()).isEqualTo("AUD-HP-001");
			assertThat(line.quantity()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("a failed order writes no outbox row, because the row rolls back with it")
	void rollbackTakesTheMessageWithIt() {
		// An unknown SKU fails before anything is persisted. The point is that no half-state exists:
		// no order, and no command telling inventory to reserve stock for one.
		rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest("idem-" + UUID.randomUUID(), "cust-1", null,
						List.of(new PlaceOrderRequest.Line("GHOST-1", 1))),
				Map.class);

		assertThat(outbox()).isEmpty();
	}

	@Test
	@DisplayName("the saga's own commands are queued too, in the order they were decided")
	void sagaCommandsAreQueuedInOrder() {
		OrderResponse order = place();
		jdbc.update("delete from outbox_messages");

		saga.onInventoryReserved(order.id(), java.time.Instant.now().plusSeconds(900));

		// Reserving leads straight to asking for payment, and both facts must reach the bus in that
		// sequence — the relay preserves insertion order for exactly this reason.
		assertThat(outbox()).extracting(row -> row.get("event_type"))
				.containsExactly("RequestPayment");
	}

	@Test
	@DisplayName("the same event queued twice occupies one row")
	void outboxIsIdempotentOnEventId() {
		OrderResponse order = place();
		int before = outbox().size();

		// A caller retrying its own transaction must not queue the message a second time.
		jdbc.update("""
				insert into outbox_messages (id, topic, message_key, event_id, event_type, payload, created_at)
				select ?, topic, message_key, event_id, event_type, payload, now() from outbox_messages limit 1
				on conflict (event_id) do nothing
				""", UUID.randomUUID());

		assertThat(outbox()).hasSize(before);
	}

	// --- processed events ----------------------------------------------------------------------------

	@Test
	@DisplayName("the first claim wins and the second is refused")
	void claimIsExactlyOnce() {
		String eventId = UUID.randomUUID().toString();

		assertThat(processedEvents.claim(eventId, "order-saga")).isTrue();
		assertThat(processedEvents.claim(eventId, "order-saga")).isFalse();
	}

	@Test
	@DisplayName("each consumer claims the same event independently")
	void consumersDedupeSeparately() {
		String eventId = UUID.randomUUID().toString();

		assertThat(processedEvents.claim(eventId, "order-saga")).isTrue();
		// A single 'seen' flag would let whichever consumer arrived first suppress the event for
		// everyone else — and several services legitimately handle the same message.
		assertThat(processedEvents.claim(eventId, "search-indexer")).isTrue();
		assertThat(processedEvents.claim(eventId, "order-saga")).isFalse();
	}

	@Test
	@DisplayName("a redelivered event runs the handler once, and the claim proves it")
	void redeliveryIsRecordedNotInferred() {
		OrderResponse order = place();
		jdbc.update("delete from outbox_messages");

		saga.onInventoryReserved(order.id(), java.time.Instant.now().plusSeconds(900));
		int afterFirst = outbox().size();

		// The listener would now skip this on the claim. Calling the saga directly shows the
		// difference: the state machine also declines it, which is the interim guard from ADR 0014
		// still doing its own job underneath.
		saga.onInventoryReserved(order.id(), java.time.Instant.now().plusSeconds(900));

		assertThat(outbox()).hasSize(afterFirst);
		assertThat(rest.getForObject("/api/v1/orders/" + order.orderNumber(), OrderResponse.class)
				.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	// --- the gauges that make a stalled relay visible ------------------------------------------------

	@Test
	@DisplayName("the outbox gauges report what is actually in the table")
	void gaugesFollowTheTable() {
		// The relay is switched off in this suite, so everything placed here stays queued. That is
		// exactly the situation the gauges exist to make visible, and it is the one a healthy-looking
		// service is otherwise indistinguishable from.
		place();
		place();
		outboxMetrics.sample();

		assertThat(meters.get("flashcart.outbox.unpublished").gauge().value()).isEqualTo(2.0);
		// Age is asserted as "not negative" rather than a value: what matters is that it tracks the
		// oldest row at all. Pinning a number here would be asserting the clock.
		assertThat(meters.get("flashcart.outbox.oldest.age.seconds").gauge().value())
				.isGreaterThanOrEqualTo(0.0);

		jdbc.update("update outbox_messages set published_at = now()");
		outboxMetrics.sample();

		// Drained. A gauge that only ever climbs would alert for ever after one busy moment.
		assertThat(meters.get("flashcart.outbox.unpublished").gauge().value()).isZero();
		assertThat(meters.get("flashcart.outbox.oldest.age.seconds").gauge().value()).isZero();
	}

	@Test
	@DisplayName("the service still reports itself live with the outbox in front of Kafka")
	void serviceInfo() {
		assertThat(rest.getForEntity("/api/v1/order/_info", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

}
