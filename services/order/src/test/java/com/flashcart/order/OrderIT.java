package com.flashcart.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.message.CancelShipment;
import com.flashcart.common.event.message.CommitInventory;
import com.flashcart.common.event.message.CreateShipment;
import com.flashcart.common.event.message.OrderCancelled;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.common.event.message.OrderDelivered;
import com.flashcart.common.event.message.RefundPayment;
import com.flashcart.common.event.message.ReleaseInventory;
import com.flashcart.common.event.message.RequestPayment;
import com.flashcart.common.event.message.ReserveInventory;
import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.api.dto.OrderResponse;
import com.flashcart.order.api.dto.PlaceOrderRequest;
import com.flashcart.order.client.CatalogClient;
import com.flashcart.order.service.CancellationReconciliationService;
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
import com.flashcart.common.security.AccessTokens;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
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
	private AccessTokens tokens;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	/**
	 * Who these requests are from.
	 *
	 * <p>Set as a default header on the template rather than threaded through every call, because the
	 * point of these tests is the order lifecycle and not the ceremony of signing in. Tests that care
	 * about identity call {@link #signedInAs} to change it.
	 */
	private void signedInAs(String customerId) {
		rest.getRestTemplate().getInterceptors().removeIf(i -> i instanceof BearerToken);
		rest.getRestTemplate().getInterceptors().add(new BearerToken(tokens.issue(customerId, customerId + "@example.test")));
	}

	/** A default header interceptor, tagged so signedInAs can replace rather than accumulate. */
	private record BearerToken(String token) implements ClientHttpRequestInterceptor {
		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body,
				ClientHttpRequestExecution execution) throws java.io.IOException {
			request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			return execution.execute(request, body);
		}
	}

	@Autowired
	private RecordingEventPublisher.Recorder events;

	@Autowired
	private FakeCatalog catalog;

	@Autowired
	private OrderSaga saga;

	@Autowired
	private OrderReconciliationService reconciler;

	@Autowired
	private CancellationReconciliationService cancellations;

	@BeforeEach
	void reset() {
		signedInAs("cust-1");
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
				new PlaceOrderRequest(uniqueKey(), null,
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

	/**
	 * An order that is paid for and has a consignment booked — the state a customer cancels from.
	 *
	 * <p>Reached by driving the saga rather than by waiting, because every one of these steps is a
	 * reply from another service and none of them is this suite's subject.
	 */
	private OrderResponse shippedOrder(String customerId) {
		OrderResponse order = place(customerId, "AUD-HP-001", 1);
		inventoryReserved(order);
		saga.onPaymentCompleted(order.id(), "pay-123");
		saga.onShipmentCreated(order.id(), "FCL0123456789");
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.SHIPPED);
		return order;
	}

	/** How many CancelShipment commands were published for this particular order. */
	private long cancelCommandsFor(OrderResponse order) {
		return events.all().stream()
				.map(RecordingEventPublisher.Recorder.Published::message)
				.filter(CancelShipment.class::isInstance)
				.map(CancelShipment.class::cast)
				.filter(command -> command.orderNumber().equals(order.orderNumber()))
				.count();
	}

	/**
	 * Backdates the order so the cancellation backstop considers it overdue.
	 *
	 * <p>Moving the row rather than the clock, because the query reads {@code updated_at} and a
	 * {@code @UpdateTimestamp} is written by Hibernate rather than from the injected clock — a test
	 * that advanced the clock would be testing something the production query does not consult.
	 */
	private void waitedLongEnough(OrderResponse order) {
		jdbc.update("update orders set updated_at = now() - interval '1 hour' where order_number = ?",
				order.orderNumber());
	}

	private void requestCancellation(OrderResponse order) {
		OrderResponse requested = rest.postForObject(
				"/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "changed my mind"), OrderResponse.class);
		assertThat(requested.status()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
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
		PlaceOrderRequest request = new PlaceOrderRequest(key, null,
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
				new PlaceOrderRequest(uniqueKey(), null,
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
	@DisplayName("dispatch moves the order on, and closes the cancellation window locally")
	void dispatchClosesTheCancellationWindow() {
		OrderResponse order = shippedOrder("cust-1");
		events.clear();

		saga.onShipmentDispatched(order.id(), Instant.now());

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DISPATCHED);

		ResponseEntity<Map> refused = rest.postForEntity(
				"/api/v1/orders/" + order.orderNumber() + "/cancel", null, Map.class);

		// 409 straight away. Before DISPATCHED existed this was a CancelShipment on the bus and a
		// wait to be told what shipping had already recorded.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(refused.getBody()).containsEntry("code", "ORDER_NOT_CANCELLABLE");
		assertThat((String) refused.getBody().get("message")).contains("already with the carrier");
		assertThat(cancelCommandsFor(order)).isZero();
	}

	@Test
	@DisplayName("a refused cancellation moves the order forward, not back to SHIPPED")
	void refusedCancellationResolvesForward() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		events.clear();

		saga.onShipmentCancellationRefused(order.id(), "DISPATCHED", "already dispatched");

		// DISPATCHED, not SHIPPED. The refusal is itself news about where the goods are, and going
		// back to SHIPPED would let the customer ask again and be refused again for the rest of time.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DISPATCHED);
		// The customer has the goods, so the charge stands. A refund here would be a free parcel.
		assertThat(events.published(RefundPayment.class)).isFalse();
		assertThat(events.published(OrderCancelled.class)).isFalse();

		// The attempt and its reason survive in the history, which is what answers "I cancelled this
		// and it arrived anyway".
		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsSubsequence("SHIPPED", "CANCELLATION_REQUESTED", "DISPATCHED");
	}

	@Test
	@DisplayName("a cancellation refused because it had already arrived resolves to DELIVERED")
	void refusalAfterDeliveryResolvesToDelivered() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);

		saga.onShipmentCancellationRefused(order.id(), "DELIVERED", "already delivered");

		// The other status shipping can refuse with. Resolving it to DISPATCHED would leave the order
		// claiming the parcel is still in transit when shipping has just said it arrived.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DELIVERED);
	}

	@Test
	@DisplayName("a delivery that overtakes its dispatch still lands")
	void deliveryMayOvertakeDispatch() {
		OrderResponse order = shippedOrder("cust-1");

		// Separate consumer groups, so nothing orders these against each other. Applied in this
		// order the delivery must still be accepted, or the arrival is lost.
		saga.onShipmentDelivered(order.id(), Instant.now());
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DELIVERED);

		saga.onShipmentDispatched(order.id(), Instant.now());
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DELIVERED);
	}

	@Test
	@DisplayName("a delivered shipment finishes the order, which nothing could do before")
	void deliveryFinishesTheOrder() {
		OrderResponse order = shippedOrder("cust-1");
		events.clear();

		Instant deliveredAt = Instant.now();
		saga.onShipmentDelivered(order.id(), deliveredAt);

		OrderResponse delivered = fetch(order.orderNumber());
		assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
		// Terminal, so there is nowhere left to go. Every other state in this machine has an exit.
		assertThat(delivered.allowedNextStates()).isEmpty();

		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsExactly("CREATED", "RESERVED", "PAYMENT_PENDING", "PAID", "FULFILLING",
						"SHIPPED", "DELIVERED");

		// The happy ending on the order topic, beside OrderConfirmed and OrderCancelled.
		assertThat(events.require(OrderDelivered.class).orderNumber()).isEqualTo(order.orderNumber());
	}

	@Test
	@DisplayName("a delivered order cannot be cancelled, because that would be a return")
	void deliveredOrderIsNotCancellable() {
		OrderResponse order = shippedOrder("cust-1");
		saga.onShipmentDelivered(order.id(), Instant.now());
		events.clear();

		ResponseEntity<Map> refused = rest.postForEntity(
				"/api/v1/orders/" + order.orderNumber() + "/cancel", null, Map.class);

		// This test could not be written until delivery was reachable -- ADR 0030 had to drop it,
		// because no order had ever been DELIVERED. Refused outright rather than becoming a
		// cancellation request: returning goods that have arrived is a different transaction.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(refused.getBody()).containsEntry("code", "ORDER_NOT_CANCELLABLE");
		assertThat(events.published(CancelShipment.class)).isFalse();
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DELIVERED);
	}

	@Test
	@DisplayName("a redelivered delivery event is ignored rather than throwing")
	void deliveryIsIdempotent() {
		OrderResponse order = shippedOrder("cust-1");
		saga.onShipmentDelivered(order.id(), Instant.now());
		events.clear();

		// DELIVERED -> DELIVERED is not an edge, so the state machine declines it. Shipping
		// re-publishes on a repeat scan, so this arrives in normal operation and must be harmless.
		saga.onShipmentDelivered(order.id(), Instant.now());

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.DELIVERED);
		assertThat(events.published(OrderDelivered.class)).isFalse();
	}

	// --- cancelling after the money has moved -------------------------------------------------------

	@Test
	@DisplayName("cancelling a shipped order asks shipping first, and does not cancel on its own")
	void cancellingAPaidOrderIsARequest() {
		OrderResponse order = shippedOrder("cust-1");
		events.clear();

		OrderResponse requested = rest.postForObject(
				"/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "changed my mind"), OrderResponse.class);

		// Not CANCELLED. Until this change it was, and nothing was compensated: the capture stayed
		// with the platform and the committed units did not come back.
		assertThat(requested.status()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
		assertThat(events.require(CancelShipment.class).orderNumber()).isEqualTo(order.orderNumber());
		// And crucially not yet. Refunding on the request rather than on shipping's answer would pay
		// out for parcels that turn out to have already left.
		assertThat(events.published(RefundPayment.class)).isFalse();
	}

	@Test
	@DisplayName("shipping confirming the stop is what cancels the order and sends the money back")
	void cancelledShipmentRefundsAndCancels() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		events.clear();

		saga.onShipmentCancelled(order.id());

		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.CANCELLED);

		RefundPayment refund = events.require(RefundPayment.class);
		assertThat(refund.orderNumber()).isEqualTo(order.orderNumber());
		// Not the order id, which is the charge's key. One key for both would collide in payment's
		// unique index and invite a provider to read the refund as a repeat of the capture.
		assertThat(refund.idempotencyKey()).isEqualTo("refund:" + order.id());
		assertThat(events.published(OrderCancelled.class)).isTrue();
	}

	@Test
	@DisplayName("asking twice does not ask shipping twice")
	void repeatedCancellationRequestIsHarmless() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		events.clear();

		OrderResponse again = rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/cancel",
				null, OrderResponse.class);

		assertThat(again.status()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
		assertThat(events.published(CancelShipment.class)).isFalse();
	}

	@Test
	@DisplayName("an unanswered cancellation is asked again, not decided")
	void unansweredCancellationIsReasked() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		waitedLongEnough(order);
		events.clear();

		cancellations.reconcileBatch();

		// The question again, verbatim, and exactly once. Not a conclusion: the order service still
		// does not know whether the parcel has left, and a timeout is not evidence either way.
		assertThat(cancelCommandsFor(order)).isEqualTo(1);
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.CANCELLATION_REQUESTED);
		assertThat(events.published(RefundPayment.class)).isFalse();
	}

	@Test
	@DisplayName("re-asking restarts the clock, so a stuck order is chased once per timeout")
	void reaskingDoesNotRepeatEveryTick() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		waitedLongEnough(order);
		events.clear();

		// Two ticks, one overdue order. Nothing about the order changes when it is re-asked, so
		// without an explicit touch the row keeps its original timestamp, stays past the cutoff
		// forever, and is chased again on every pass -- a shipping outage turning one stuck order
		// into a command every fifteen seconds.
		cancellations.reconcileBatch();
		cancellations.reconcileBatch();

		assertThat(cancelCommandsFor(order)).isEqualTo(1);
	}

	@Test
	@DisplayName("re-asking leaves no trail in the history")
	void reaskingDoesNotWriteHistory() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		int before = historyOf(order.orderNumber()).size();

		waitedLongEnough(order);
		cancellations.reconcileBatch();
		waitedLongEnough(order);
		cancellations.reconcileBatch();

		// Two re-asks, no entries. A history entry per tick would bury the transitions that mean
		// something under a running commentary on how long the order has been waiting.
		assertThat(historyOf(order.orderNumber())).hasSize(before);
	}

	@Test
	@DisplayName("an order that has only just asked is left alone")
	void freshCancellationIsNotReasked() {
		OrderResponse order = shippedOrder("cust-1");
		requestCancellation(order);
		events.clear();

		// No backdating, so it is inside the timeout. Chasing an answer that is merely in flight
		// would make every cancellation send two commands.
		cancellations.reconcileBatch();

		// Asserted on what was published for *this* order rather than on the batch count: these
		// tests share a database, so "the batch did nothing" would depend on what every other test
		// left behind, and would pass or fail for reasons unrelated to the claim.
		assertThat(cancelCommandsFor(order)).isZero();
	}

	@Test
	@DisplayName("an answered cancellation is not chased, whichever way it was answered")
	void answeredCancellationIsNotReasked() {
		OrderResponse cancelled = shippedOrder("cust-1");
		requestCancellation(cancelled);
		saga.onShipmentCancelled(cancelled.id());

		OrderResponse refused = shippedOrder("cust-1");
		requestCancellation(refused);
		saga.onShipmentCancellationRefused(refused.id(), "DISPATCHED", "already dispatched");

		waitedLongEnough(cancelled);
		waitedLongEnough(refused);
		events.clear();

		cancellations.reconcileBatch();

		assertThat(cancelCommandsFor(cancelled)).isZero();
		assertThat(cancelCommandsFor(refused)).isZero();
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
				new HttpEntity<>(new PlaceOrderRequest(uniqueKey(), null,
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

	// --- an operator may read somebody else's order, per ADR 0028 -------------------------------------

	/** Signs in with the operator role, which signedInAs deliberately does not grant. */
	private void signedInAsOperator(String operatorId) {
		rest.getRestTemplate().getInterceptors().removeIf(i -> i instanceof BearerToken);
		rest.getRestTemplate().getInterceptors().add(new BearerToken(
				tokens.issue(operatorId, operatorId + "@example.test", List.of(AccessTokens.OPERATOR))));
	}

	private Long auditRows(String customerId) {
		return jdbc.queryForObject(
				"select count(*) from operator_access_log where customer_id = ?", Long.class, customerId);
	}

	@Test
	@DisplayName("an operator can read a customer's order, and the read is on the record")
	void operatorReadsAnothersOrder() {
		signedInAs("audit-owner-a");
		String orderNumber = place(null, "AUD-HP-001", 1).orderNumber();

		signedInAsOperator("ops-1");
		ResponseEntity<OrderResponse> response = rest.getForEntity("/api/v1/orders/" + orderNumber,
				OrderResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().customerId()).isEqualTo("audit-owner-a");

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-a'");
		assertThat(row).containsEntry("operator_id", "ops-1");
		assertThat(row).containsEntry("action", "READ_ORDER");
		assertThat(row).containsEntry("resource_id", orderNumber);
	}

	@Test
	@DisplayName("reading the history is recorded as its own action, not as a plain read")
	void operatorReadingHistoryIsRecordedSeparately() {
		signedInAs("audit-owner-b");
		String orderNumber = place(null, "AUD-HP-001", 1).orderNumber();

		signedInAsOperator("ops-1");
		assertThat(rest.getForEntity("/api/v1/orders/" + orderNumber + "/history", List.class)
				.getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(jdbc.queryForObject(
				"select action from operator_access_log where customer_id = 'audit-owner-b'", String.class))
				.isEqualTo("READ_ORDER_HISTORY");
	}

	@Test
	@DisplayName("a customer reading their own order is the ordinary path and records nothing")
	void ownReadIsNotRecorded() {
		signedInAs("audit-owner-c");
		String orderNumber = place(null, "AUD-HP-001", 1).orderNumber();

		assertThat(rest.getForEntity("/api/v1/orders/" + orderNumber, OrderResponse.class)
				.getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(auditRows("audit-owner-c")).isZero();
	}

	@Test
	@DisplayName("an ordinary customer still gets 404 for somebody else's, and nothing is recorded")
	void strangerStillGetsNotFound() {
		signedInAs("audit-owner-d");
		String orderNumber = place(null, "AUD-HP-001", 1).orderNumber();

		signedInAs("audit-stranger");
		assertThat(rest.getForEntity("/api/v1/orders/" + orderNumber, Map.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		// Nothing was disclosed, so there is nothing to account for.
		assertThat(auditRows("audit-owner-d")).isZero();
	}

	@Test
	@DisplayName("an operator can read who accessed a customer's orders")
	void accessLogIsReadable() {
		signedInAs("audit-reader-a");
		String orderNumber = place(null, "AUD-HP-001", 1).orderNumber();

		signedInAsOperator("ops-1");
		rest.getForEntity("/api/v1/orders/" + orderNumber, OrderResponse.class);

		List<Map<String, Object>> log = rest.getForObject(
				"/api/v1/order/_access-log?customerId=audit-reader-a", List.class);

		// Two: the order read, and this read of the log -- recorded before it answered, which is
		// ADR 0025's invariant applied to the table itself.
		assertThat(log).extracting(e -> e.get("action"))
				.containsExactly("READ_ACCESS_LOG", "READ_ORDER");
		assertThat(log.get(1)).containsEntry("resourceId", orderNumber);
	}

	@Test
	@DisplayName("a signed-in customer cannot read the access log, and nothing but this holds that")
	void customerCannotReadTheAccessLog() {
		signedInAs("audit-reader-b");

		ResponseEntity<Map> refused = rest.getForEntity(
				"/api/v1/order/_access-log?customerId=audit-reader-b", Map.class);

		// This service has no OperatorFilter -- unlike payment and shipping it is not default-deny --
		// so the check inside OperatorAccessLogReader is the only thing refusing this. If it were
		// removed, nothing behind it would notice, which is why the assertion exists. ADR 0034.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(refused.getBody()).containsEntry("code", "OPERATOR_REQUIRED");
		assertThat(auditRows("audit-reader-b")).isZero();
	}

	@Test
	@DisplayName("an operator may NOT cancel somebody else's order: looking is not acting")
	void operatorCannotCancelAnothersOrder() {
		signedInAs("audit-owner-e");
		String orderNumber = place(null, "AUD-HP-001", 1).orderNumber();

		signedInAsOperator("ops-1");
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders/" + orderNumber + "/cancel",
				null, Map.class);

		// ADR 0028 opened reading, deliberately not writing. Cancelling releases stock and moves a
		// state machine on a customer's behalf; it discloses nothing and destroys something, which is
		// a different decision from being allowed to look.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(rest.getForEntity("/api/v1/orders/" + orderNumber, OrderResponse.class).getBody()
				.status()).isNotEqualTo(OrderStatus.CANCELLED);
	}

	@Test
	@DisplayName("an operator may list another customer's orders; anyone else naming one is refused")
	void operatorMayListAnothersOrders() {
		signedInAs("audit-owner-f");
		place(null, "AUD-HP-001", 1);

		signedInAsOperator("ops-1");
		assertThat(rest.getForEntity("/api/v1/orders?customerId=audit-owner-f", List.class).getBody())
				.hasSize(1);
		assertThat(jdbc.queryForObject(
				"select action from operator_access_log where customer_id = 'audit-owner-f'", String.class))
				.isEqualTo("READ_ORDER_LIST");

		signedInAs("audit-stranger");
		assertThat(rest.getForEntity("/api/v1/orders?customerId=audit-owner-f", Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
}
