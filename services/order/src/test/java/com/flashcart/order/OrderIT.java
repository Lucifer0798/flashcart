package com.flashcart.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.message.CommitInventory;
import com.flashcart.common.event.message.CreateShipment;
import com.flashcart.common.event.message.OrderCancelled;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.common.event.message.ReleaseInventory;
import com.flashcart.common.event.message.RequestPayment;
import com.flashcart.common.event.message.ReserveInventory;
import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.api.dto.OrderResponse;
import com.flashcart.order.api.dto.PlaceOrderRequest;
import com.flashcart.order.client.CatalogClient;
import com.flashcart.order.service.OrderReconciliationService;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order service against a real PostgreSQL, with the bus captured rather than run.
 *
 * <p>Placing an order is now asynchronous, so these tests come in two halves: what the HTTP call does
 * (persist, publish, return 202), and what the saga does when a reply arrives. The second half is
 * driven by calling {@link OrderSaga} directly, which is exactly what the Kafka listener does — the
 * listener itself is thin plumbing, and the round trip through a real broker is proved separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({ RecordingEventPublisher.class, OrderIT.FakeCatalogConfig.class })
@TestPropertySource(properties = {
		"flashcart.order.reconciler.enabled=false",
		// This suite records what the service decides, so its own publisher must win. Turning the
		// queue off leaves the consumer-side dedup beans in place, which the listeners still need.
		"flashcart.outbox.enabled=false",
		// No listener containers: this suite drives the saga directly and there is no broker to
		// connect to, so leaving them on would just log connection failures for the whole run.
		"spring.kafka.listener.auto-startup=false"
})
class OrderIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	/** A catalog with whatever products a test puts in it. */
	@TestConfiguration
	static class FakeCatalogConfig {

		@Bean
		@Primary
		FakeCatalog fakeCatalog() {
			return new FakeCatalog();
		}
	}

	static class FakeCatalog implements CatalogClient {

		private final Map<String, PricedProduct> products = new ConcurrentHashMap<>();

		void stock(String sku, String name, String price, String currency) {
			products.put(sku, new PricedProduct(sku, name, new BigDecimal(price), currency, false));
		}

		void clear() {
			products.clear();
		}

		@Override
		public PricedProduct priceOf(String sku) {
			PricedProduct product = products.get(sku);
			if (product == null) {
				throw ResourceNotFoundException.of("Product with SKU", sku);
			}
			return product;
		}
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private RecordingEventPublisher.Recorder events;

	@Autowired
	private FakeCatalog catalog;

	@Autowired
	private OrderSaga saga;

	@Autowired
	private OrderReconciliationService reconciler;

	@BeforeEach
	void reset() {
		events.clear();
		catalog.clear();
		catalog.stock("AUD-HP-001", "Aurora Over-Ear Headphones", "179.00", "USD");
		catalog.stock("WEA-WT-001", "Meridian Smartwatch", "299.00", "USD");
	}

	// --- helpers ---------------------------------------------------------------------------------

	private static String uniqueKey() {
		return "idem-" + UUID.randomUUID();
	}

	private OrderResponse place(String customerId, String sku, int quantity) {
		ResponseEntity<OrderResponse> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), customerId, null,
						List.of(new PlaceOrderRequest.Line(sku, quantity))),
				OrderResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		return response.getBody();
	}

	private OrderResponse fetch(String orderNumber) {
		return rest.getForObject("/api/v1/orders/" + orderNumber, OrderResponse.class);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> historyOf(String orderNumber) {
		return rest.getForObject("/api/v1/orders/" + orderNumber + "/history", List.class);
	}

	/** Simulates inventory replying that it holds the stock. */
	private void inventoryReserved(OrderResponse order) {
		saga.onInventoryReserved(order.id(), Instant.now().plus(15, ChronoUnit.MINUTES));
	}

	// --- placing an order --------------------------------------------------------------------------

	@Test
	@DisplayName("placing an order returns 202 with a CREATED order and asks inventory to hold stock")
	void placeIsAcceptedNotCompleted() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 2);

		// 202, not 201: the order exists, but whether it got the stock is not known yet.
		assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.total()).isEqualByComparingTo("358.00");

		ReserveInventory command = events.require(ReserveInventory.class);
		assertThat(command.reservationKey()).isEqualTo(order.id().toString());
		assertThat(command.customerId()).isEqualTo("cust-1");
		assertThat(command.lines()).singleElement()
				.satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
		// Keyed by order id, which is what makes Kafka deliver one order's messages in sequence.
		assertThat(command.aggregateId()).isEqualTo(order.id().toString());
	}

	@Test
	@DisplayName("prices still come from catalog, not from the request")
	void pricesComeFromCatalog() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);

		assertThat(order.lines()).singleElement().satisfies(line -> {
			assertThat(line.productName()).isEqualTo("Aurora Over-Ear Headphones");
			assertThat(line.unitPrice()).isEqualByComparingTo("179.00");
		});
	}

	@Test
	@DisplayName("a retried checkout returns the original order and re-sends the reservation command")
	void placeIsIdempotentAndResends() {
		String key = uniqueKey();
		PlaceOrderRequest request = new PlaceOrderRequest(key, "cust-1", null,
				List.of(new PlaceOrderRequest.Line("AUD-HP-001", 1)));

		OrderResponse first = rest.postForEntity("/api/v1/orders", request, OrderResponse.class).getBody();
		OrderResponse retry = rest.postForEntity("/api/v1/orders", request, OrderResponse.class).getBody();

		assertThat(retry.id()).isEqualTo(first.id());
		// Two commands, one order. Re-sending is deliberate: the order is still CREATED, so the first
		// command may never have reached the broker — and ReserveInventory is idempotent on the
		// reservation key, so a duplicate costs nothing.
		assertThat(events.countOf(ReserveInventory.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("an unknown SKU is a 404 and nothing is published")
	void unknownSkuPublishesNothing() {
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), "cust-1", null,
						List.of(new PlaceOrderRequest.Line("GHOST-1", 1))), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(events.all()).isEmpty();
	}

	// --- the saga: happy path ------------------------------------------------------------------------

	@Test
	@DisplayName("stock held moves the order to PAYMENT_PENDING and asks for the money in one step")
	void reservedGoesStraightToRequestingPayment() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		events.clear();

		inventoryReserved(order);

		// Not RESERVED-and-waiting: the hold is ticking, so the saga asks for payment immediately.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
		RequestPayment payment = events.require(RequestPayment.class);
		assertThat(payment.amount()).isEqualByComparingTo("179.00");
		// The order id again, all the way down to the provider: a customer charged twice for one
		// order is the most expensive possible consequence of at-least-once delivery.
		assertThat(payment.idempotencyKey()).isEqualTo(order.id().toString());

		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsExactly("CREATED", "RESERVED", "PAYMENT_PENDING");
	}

	@Test
	@DisplayName("payment completing commits the stock, books the shipment, and confirms the order")
	void paymentCompletedFansOut() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);
		events.clear();

		saga.onPaymentCompleted(order.id(), "pay-123");

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.FULFILLING);
		// Only now do the units actually leave the warehouse.
		assertThat(events.require(CommitInventory.class).reservationKey())
				.isEqualTo(order.id().toString());
		assertThat(events.require(CreateShipment.class).orderNumber()).isEqualTo(order.orderNumber());
		assertThat(events.published(OrderConfirmed.class)).isTrue();
	}

	@Test
	@DisplayName("a shipment being booked moves the order to SHIPPED")
	void shipmentCreatedShipsTheOrder() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);
		saga.onPaymentCompleted(order.id(), "pay-123");

		saga.onShipmentCreated(order.id(), "FCL0123456789");

		OrderResponse shipped = fetch(order.orderNumber());
		assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsExactly("CREATED", "RESERVED", "PAYMENT_PENDING", "PAID", "FULFILLING",
						"SHIPPED");
	}

	// --- the saga: compensations ---------------------------------------------------------------------

	@Test
	@DisplayName("a refused reservation cancels the order and carries inventory's own code")
	void reservationFailedCancels() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		events.clear();

		saga.onReservationFailed(order.id(), "INSUFFICIENT_STOCK", "Only 0 units available");

		OrderResponse cancelled = fetch(order.orderNumber());
		assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.cancellationReason()).contains("INSUFFICIENT_STOCK");
		// Nothing was held, so there is nothing to release — and no release is published.
		assertThat(events.published(ReleaseInventory.class)).isFalse();
		assertThat(events.published(OrderCancelled.class)).isTrue();
	}

	@Test
	@DisplayName("a declined payment releases the stock and cancels, recording PAYMENT_FAILED on the way")
	void paymentFailedCompensates() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);
		events.clear();

		saga.onPaymentFailed(order.id(), "CARD_DECLINED", "The card was declined by the issuer");

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(events.require(ReleaseInventory.class).reservationKey())
				.isEqualTo(order.id().toString());
		// The intermediate state is persisted rather than skipped, so the history says *why*.
		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsExactly("CREATED", "RESERVED", "PAYMENT_PENDING", "PAYMENT_FAILED", "CANCELLED");
	}

	@Test
	@DisplayName("a payment timeout stops at PAYMENT_TIMEOUT and releases nothing")
	void paymentTimeoutDoesNotRelease() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);
		events.clear();

		saga.onPaymentTimedOut(order.id());

		// The crucial one. The charge may still land, so releasing the stock here could sell the
		// same unit twice and then owe a refund. It waits for reconciliation instead.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_TIMEOUT);
		assertThat(events.published(ReleaseInventory.class)).isFalse();
	}

	@Test
	@DisplayName("an expired reservation cancels the order without asking for a release it already got")
	void reservationExpiredCancels() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		// Straight from RESERVED — the saga would normally have gone on to PAYMENT_PENDING, but an
		// expiry arriving first is exactly the race this has to survive.
		saga.onInventoryReserved(order.id(), Instant.now().minusSeconds(1));
		events.clear();

		saga.onReservationExpired(order.id());

		// PAYMENT_PENDING by now, so RESERVATION_EXPIRED is not reachable and the event is ignored
		// rather than throwing — which is the idempotency guard doing its job.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	// --- idempotency ----------------------------------------------------------------------------------

	@Test
	@DisplayName("a redelivered event is ignored rather than dead-lettered")
	void redeliveryIsIgnored() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);
		events.clear();

		// The same event again — a rebalance alone causes this. It must not throw, because a
		// consumer that throws on a duplicate eventually dead-letters a message that was fine.
		inventoryReserved(order);

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
		// And crucially, no second payment request: the customer is not charged twice.
		assertThat(events.published(RequestPayment.class)).isFalse();
	}

	@Test
	@DisplayName("a duplicate payment completion does not book a second shipment")
	void duplicatePaymentCompletionIsIgnored() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);
		saga.onPaymentCompleted(order.id(), "pay-123");
		events.clear();

		saga.onPaymentCompleted(order.id(), "pay-123");

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.FULFILLING);
		assertThat(events.published(CreateShipment.class)).isFalse();
	}

	@Test
	@DisplayName("an event for an order that does not exist is ignored, not an error")
	void unknownOrderIsIgnored() {
		saga.onPaymentCompleted(UUID.randomUUID(), "pay-ghost");

		assertThat(events.all()).isEmpty();
	}

	// --- cancellation and the reconciler ----------------------------------------------------------------

	@Test
	@DisplayName("cancelling a held order asks inventory to release it")
	void cancelReleases() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		saga.onInventoryReserved(order.id(), Instant.now().plus(15, ChronoUnit.MINUTES));
		events.clear();

		// PAYMENT_PENDING by now, so cancelling is refused — a charge is in flight.
		ResponseEntity<Map> refused = rest.postForEntity("/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "changed my mind"), Map.class);
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(refused.getBody()).containsEntry("code", "ORDER_NOT_CANCELLABLE");
		assertThat(events.published(ReleaseInventory.class)).isFalse();
	}

	@Test
	@DisplayName("a CREATED order can still be cancelled, and nothing is released because nothing is held")
	void cancelBeforeReservation() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		events.clear();

		OrderResponse cancelled = rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "changed my mind"), OrderResponse.class);

		assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(events.published(ReleaseInventory.class)).isFalse();
	}

	@Test
	@DisplayName("the reconciler catches an order whose expiry event never arrived")
	void reconcilerIsTheBackstop() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		// Reserved with a deadline already in the past, and no expiry event ever published — the
		// exact situation the reconciler exists for.
		saga.onInventoryReserved(order.id(), Instant.now().minusSeconds(60));
		events.clear();

		// PAYMENT_PENDING, so the reconciler must leave it alone: a charge is in flight.
		assertThat(reconciler.reconcileBatch()).isZero();
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	// --- cross-cutting ------------------------------------------------------------------------------------

	@Test
	@DisplayName("the correlation id reaches the published command, not just the log")
	void correlationIdTravelsOnTheMessage() {
		String correlationId = UUID.randomUUID().toString();
		HttpHeaders headers = new HttpHeaders();
		headers.set(CorrelationId.HEADER, correlationId);

		rest.exchange("/api/v1/orders", HttpMethod.POST,
				new HttpEntity<>(new PlaceOrderRequest(uniqueKey(), "cust-1", null,
						List.of(new PlaceOrderRequest.Line("AUD-HP-001", 1))), headers),
				OrderResponse.class);

		// Without this a checkout stops being traceable the moment it crosses the bus.
		assertThat(events.require(ReserveInventory.class).correlationId()).isEqualTo(correlationId);
	}

	@Test
	@DisplayName("every published message carries a unique event id")
	void eventIdsAreUnique() {
		OrderResponse order = place("cust-1", "AUD-HP-001", 1);
		inventoryReserved(order);

		List<String> ids = events.all().stream().map(p -> p.message().eventId()).toList();

		// The basis of every consumer's idempotency. A repeated id would defeat all of them at once.
		assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isNotBlank());
	}

	@Test
	@DisplayName("the service reports itself live")
	void serviceInfo() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/order/_info", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "live");
	}
}
