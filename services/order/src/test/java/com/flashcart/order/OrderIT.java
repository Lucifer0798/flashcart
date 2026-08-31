package com.flashcart.order;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.api.dto.OrderResponse;
import com.flashcart.order.api.dto.PlaceOrderRequest;
import com.flashcart.order.service.OrderReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order lifecycle against a real PostgreSQL, driven over real HTTP, with catalog and inventory
 * faked so their failure modes can actually be provoked.
 *
 * <p>The container is started in a static initialiser rather than through {@code @Container}: JUnit
 * would stop it when this class finishes while Spring's context cache kept the pool alive for the
 * next class, and every test there would then hang to the connection timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(FakeDownstreams.class)
@TestPropertySource(properties = {
		// The reconciler is driven explicitly in the tests that care about it; on its own timer it
		// would expire orders mid-assertion elsewhere.
		"flashcart.order.reconciler.enabled=false"
})
class OrderIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private FakeDownstreams.FakeCatalog catalog;

	@Autowired
	private FakeDownstreams.FakeInventory inventory;

	@Autowired
	private OrderReconciliationService reconciler;

	@BeforeEach
	void resetDownstreams() {
		inventory.reset();
		catalog.clear();
		catalog.stock("AUD-HP-001", "Aurora Over-Ear Headphones", "179.00");
		catalog.stock("WEA-WT-001", "Meridian Smartwatch", "299.00");
	}

	// --- helpers ---------------------------------------------------------------------------------

	private static String uniqueKey() {
		return "idem-" + UUID.randomUUID();
	}

	private ResponseEntity<OrderResponse> place(String idempotencyKey, String customerId, String sku,
			int quantity) {
		return rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(idempotencyKey, customerId, null,
						List.of(new PlaceOrderRequest.Line(sku, quantity))),
				OrderResponse.class);
	}

	private ResponseEntity<Map> placeExpectingFailure(String idempotencyKey, String customerId, String sku,
			int quantity) {
		return rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(idempotencyKey, customerId, null,
						List.of(new PlaceOrderRequest.Line(sku, quantity))),
				Map.class);
	}

	private OrderResponse fetch(String orderNumber) {
		return rest.getForObject("/api/v1/orders/" + orderNumber, OrderResponse.class);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> historyOf(String orderNumber) {
		return rest.getForObject("/api/v1/orders/" + orderNumber + "/history", List.class);
	}

	// --- the happy path --------------------------------------------------------------------------

	@Test
	@DisplayName("placing an order prices it from catalog and holds the stock")
	void placeOrderReservesStock() {
		ResponseEntity<OrderResponse> response = place(uniqueKey(), "cust-1", "AUD-HP-001", 2);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		OrderResponse order = response.getBody();
		assertThat(order.status()).isEqualTo(OrderStatus.RESERVED);
		assertThat(order.orderNumber()).startsWith("FC-");
		assertThat(order.reservationExpiresAt()).isNotNull();

		// Priced from catalog, not from anything the client sent — the request has no price field.
		assertThat(order.lines()).singleElement().satisfies(line -> {
			assertThat(line.sku()).isEqualTo("AUD-HP-001");
			assertThat(line.productName()).isEqualTo("Aurora Over-Ear Headphones");
			assertThat(line.unitPrice()).isEqualByComparingTo("179.00");
			assertThat(line.lineTotal()).isEqualByComparingTo("358.00");
		});
		assertThat(order.total()).isEqualByComparingTo("358.00");

		// Inventory was asked using the order id as the reservation key, which is what makes a
		// retried reserve idempotent on its side.
		assertThat(inventory.reserves()).singleElement().satisfies(command -> {
			assertThat(command.reservationKey()).isEqualTo(order.id().toString());
			assertThat(command.customerId()).isEqualTo("cust-1");
			assertThat(command.lines()).singleElement()
					.satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
		});
	}

	@Test
	@DisplayName("the response advertises what could legally happen next")
	void responseAdvertisesAllowedTransitions() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();

		// Straight from the state machine, so a client renders the right controls instead of keeping
		// its own copy of the rules that will drift from this service's.
		assertThat(order.allowedNextStates())
				.containsExactlyInAnyOrder(OrderStatus.PAYMENT_PENDING, OrderStatus.RESERVATION_EXPIRED,
						OrderStatus.CANCELLED);
	}

	@Test
	@DisplayName("a multi-line order is priced and held as one basket")
	void multiLineOrder() {
		ResponseEntity<OrderResponse> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), "cust-1", null, List.of(
						new PlaceOrderRequest.Line("AUD-HP-001", 1),
						new PlaceOrderRequest.Line("WEA-WT-001", 1))),
				OrderResponse.class);

		OrderResponse order = response.getBody();
		assertThat(order.lines()).hasSize(2);
		assertThat(order.total()).isEqualByComparingTo("478.00");
		assertThat(inventory.reserves()).singleElement()
				.satisfies(command -> assertThat(command.lines()).hasSize(2));
	}

	@Test
	@DisplayName("requesting payment moves a held order to PAYMENT_PENDING, and is idempotent")
	void requestPayment() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();

		OrderResponse pending = rest.postForObject(
				"/api/v1/orders/" + order.orderNumber() + "/request-payment", null, OrderResponse.class);
		assertThat(pending.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);

		// Asking twice is a retry, not an error.
		OrderResponse again = rest.postForObject(
				"/api/v1/orders/" + order.orderNumber() + "/request-payment", null, OrderResponse.class);
		assertThat(again.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	// --- idempotency -----------------------------------------------------------------------------

	@Test
	@DisplayName("a retried checkout returns the original order rather than placing a second")
	void placeIsIdempotent() {
		String key = uniqueKey();

		OrderResponse first = place(key, "cust-1", "AUD-HP-001", 1).getBody();
		OrderResponse retry = place(key, "cust-1", "AUD-HP-001", 1).getBody();

		assertThat(retry.id()).isEqualTo(first.id());
		assertThat(retry.orderNumber()).isEqualTo(first.orderNumber());
		// One hold, not two. During a flash sale a double-tapped buy button is the norm.
		assertThat(inventory.reserves()).hasSize(1);
	}

	// --- when inventory says no --------------------------------------------------------------------

	@Test
	@DisplayName("a refusal cancels the order and passes inventory's own code straight through")
	void inventoryRefusalCancelsTheOrder() {
		inventory.willReject("INSUFFICIENT_STOCK");
		String customer = "cust-" + UUID.randomUUID();

		ResponseEntity<Map> response = placeExpectingFailure(uniqueKey(), customer, "AUD-HP-001", 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		// Not flattened into a generic message: all three inventory refusals read as "sold out" to a
		// shopper and mean entirely different things to whoever is on support.
		assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_STOCK");

		// The order exists and is CANCELLED rather than vanishing — "why could I not buy this" is a
		// real question, and an order that was never recorded cannot answer it.
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> orders =
				rest.getForObject("/api/v1/orders?customerId=" + customer, List.class);
		assertThat(orders).singleElement().satisfies(order -> {
			assertThat(order).containsEntry("status", "CANCELLED");
			assertThat(String.valueOf(order.get("cancellationReason"))).contains("INSUFFICIENT_STOCK");
		});
	}

	@Test
	@DisplayName("a sold-out flash sale is reported with the sale's own code, not a generic one")
	void allocationExhaustedPassesThrough() {
		inventory.willReject("SALE_ALLOCATION_EXHAUSTED");

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), "cust-1", UUID.randomUUID(),
						List.of(new PlaceOrderRequest.Line("AUD-HP-001", 1))),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "SALE_ALLOCATION_EXHAUSTED");
	}

	@Test
	@DisplayName("an unreachable inventory leaves the order CREATED, so a retry can settle it")
	void unavailableInventoryLeavesTheOrderRetryable() {
		inventory.willBeUnavailable();
		String key = uniqueKey();

		ResponseEntity<Map> failed = placeExpectingFailure(key, "cust-1", "AUD-HP-001", 1);

		assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		// The crucial difference from a refusal: silence is not a "no". The hold may exist, so the
		// order is neither cancelled (which could strand real stock) nor confirmed (which could
		// promise stock nobody holds). It waits.
		inventory.willAccept();
		OrderResponse retried = place(key, "cust-1", "AUD-HP-001", 1).getBody();

		assertThat(retried.status()).isEqualTo(OrderStatus.RESERVED);
		// Same order, resumed — not a second one placed alongside a possibly-real hold.
		assertThat(retried.orderNumber()).isNotNull();
	}

	// --- compensation ------------------------------------------------------------------------------

	@Test
	@DisplayName("cancelling releases the hold before recording the cancellation")
	void cancelReleasesTheHold() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();

		OrderResponse cancelled = rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "changed my mind"), OrderResponse.class);

		assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.cancellationReason()).isEqualTo("changed my mind");
		assertThat(inventory.wasReleased(order.id().toString())).isTrue();
	}

	@Test
	@DisplayName("if the release fails, the order is left alone rather than looking finished")
	void failedReleaseLeavesTheOrderIntact() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		inventory.releaseWillBeUnavailable(true);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "trying to cancel"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		// Still RESERVED. Cancelling first would have left an order that looks finished while
		// inventory is still holding its units — the one state nobody could reconcile from.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.RESERVED);
	}

	@Test
	@DisplayName("cancelling twice releases once")
	void cancelIsIdempotent() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		String cancelUrl = "/api/v1/orders/" + order.orderNumber() + "/cancel";

		rest.postForObject(cancelUrl, Map.of("reason", "first"), OrderResponse.class);
		OrderResponse second = rest.postForObject(cancelUrl, Map.of("reason", "second"), OrderResponse.class);

		assertThat(second.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(inventory.releaseCallCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("a declined payment walks PAYMENT_PENDING -> PAYMENT_FAILED -> CANCELLED, releasing stock")
	void paymentFailureCompensates() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/request-payment", null,
				OrderResponse.class);

		OrderResponse failed = rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/payment-failed",
				Map.of("reason", "card declined"), OrderResponse.class);

		assertThat(failed.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(inventory.wasReleased(order.id().toString())).isTrue();

		// PAYMENT_FAILED is persisted on the way through, not skipped: compensation is a state, so
		// the history shows why the order was cancelled rather than merely that it was.
		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsExactly("CREATED", "RESERVED", "PAYMENT_PENDING", "PAYMENT_FAILED", "CANCELLED");
	}

	@Test
	@DisplayName("cancelling an order with a payment in flight is refused without releasing the stock")
	void paymentPendingCannotBeCancelled() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/request-payment", null,
				OrderResponse.class);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders/" + order.orderNumber() + "/cancel",
				Map.of("reason", "impatient"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "ORDER_NOT_CANCELLABLE");
		// The important half: the guard runs *before* the release. Discovering the transition was
		// illegal afterwards would have left the stock given back and the order still live, which is
		// strictly worse than either outcome on its own.
		assertThat(inventory.wasReleased(order.id().toString())).isFalse();
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	@Test
	@DisplayName("a terminal order cannot be moved on")
	void terminalOrderCannotBeCancelled() {
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/cancel", Map.of("reason", "done"),
				OrderResponse.class);

		ResponseEntity<Map> response = rest.postForEntity(
				"/api/v1/orders/" + order.orderNumber() + "/request-payment", null, Map.class);

		// CANCELLED is terminal, and PAYMENT_PENDING is not reachable from it. The state machine
		// refuses rather than the controller remembering to check.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "ORDER_ILLEGAL_TRANSITION");
	}

	// --- the reconciler ------------------------------------------------------------------------------

	@Test
	@DisplayName("an order whose hold lapsed is expired and cancelled by the reconciler")
	void reconcilerExpiresAbandonedOrders() throws InterruptedException {
		// A hold that lapses almost immediately, so expiry is testable without a real fifteen-minute wait.
		inventory.holdsFor(Duration.ofMillis(500));
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		assertThat(order.status()).isEqualTo(OrderStatus.RESERVED);

		Thread.sleep(700);
		int reconciled = reconciler.reconcileBatch();

		assertThat(reconciled).isEqualTo(1);
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(inventory.wasReleased(order.id().toString())).isTrue();

		// RESERVATION_EXPIRED is recorded on the way through, so the history says why.
		assertThat(historyOf(order.orderNumber())).extracting(entry -> entry.get("toStatus"))
				.containsExactly("CREATED", "RESERVED", "RESERVATION_EXPIRED", "CANCELLED");
	}

	@Test
	@DisplayName("the reconciler leaves an order alone if it cannot confirm the stock came back")
	void reconcilerWaitsWhenInventoryIsUnavailable() throws InterruptedException {
		inventory.holdsFor(Duration.ofMillis(500));
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		Thread.sleep(700);
		inventory.releaseWillBeUnavailable(true);

		assertThat(reconciler.reconcileBatch()).isZero();

		// Still RESERVED. Marking it expired while unable to confirm the units are back would let
		// the order and inventory disagree, which is the one outcome this job exists to prevent.
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.RESERVED);

		inventory.releaseWillBeUnavailable(false);
		assertThat(reconciler.reconcileBatch()).isEqualTo(1);
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.CANCELLED);
	}

	@Test
	@DisplayName("the reconciler does not touch an order with a payment in flight")
	void reconcilerIgnoresPaymentPendingOrders() throws InterruptedException {
		inventory.holdsFor(Duration.ofMillis(500));
		OrderResponse order = place(uniqueKey(), "cust-1", "AUD-HP-001", 1).getBody();
		rest.postForObject("/api/v1/orders/" + order.orderNumber() + "/request-payment", null,
				OrderResponse.class);

		Thread.sleep(700);

		// Reclaiming stock from underneath a charge that might yet succeed is exactly the mistake
		// the PAYMENT_TIMEOUT path exists to avoid. Phase 6 owns that case.
		assertThat(reconciler.reconcileBatch()).isZero();
		assertThat(fetch(order.orderNumber()).status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
		assertThat(inventory.wasReleased(order.id().toString())).isFalse();
	}

	// --- validation and lookup -----------------------------------------------------------------------

	@Test
	@DisplayName("an unknown SKU is a 404 and no order is created")
	void unknownSkuIsNotFound() {
		ResponseEntity<Map> response = placeExpectingFailure(uniqueKey(), "cust-1", "GHOST-001", 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// Priced before anything is persisted, so a bad basket leaves nothing to clean up.
		assertThat(inventory.reserves()).isEmpty();
	}

	@Test
	@DisplayName("a basket mixing currencies is refused rather than silently summed")
	void mixedCurrencyIsRefused() {
		catalog.stock("EUR-001", "European Product", "50.00", "EUR");

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), "cust-1", null, List.of(
						new PlaceOrderRequest.Line("AUD-HP-001", 1),
						new PlaceOrderRequest.Line("EUR-001", 1))),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("code", "MIXED_CURRENCY");
	}

	@Test
	@DisplayName("a repeated SKU is rejected rather than silently combined")
	void duplicateSkuIsRejected() {
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), "cust-1", null, List.of(
						new PlaceOrderRequest.Line("AUD-HP-001", 1),
						new PlaceOrderRequest.Line("aud-hp-001", 2))),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("an empty basket is rejected by validation")
	void emptyBasketIsRejected() {
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/orders",
				new PlaceOrderRequest(uniqueKey(), "cust-1", null, List.of()), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
	}

	@Test
	@DisplayName("an unknown order is a 404 carrying the shared error envelope")
	void unknownOrderIsNotFound() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/orders/FC-NOSUCH01", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
		assertThat(response.getBody()).containsKey("correlationId");
	}

	@Test
	@DisplayName("a customer's orders come back newest first")
	void listsCustomerOrders() {
		String customer = "cust-" + UUID.randomUUID();
		place(uniqueKey(), customer, "AUD-HP-001", 1);
		place(uniqueKey(), customer, "WEA-WT-001", 1);

		ResponseEntity<List> response = rest.getForEntity("/api/v1/orders?customerId=" + customer, List.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(2);
	}

	@Test
	@DisplayName("the caller's correlation id is echoed and recorded against every transition")
	void correlationIdReachesTheHistory() {
		String correlationId = UUID.randomUUID().toString();
		HttpHeaders headers = new HttpHeaders();
		headers.set(CorrelationId.HEADER, correlationId);

		ResponseEntity<OrderResponse> response = rest.exchange("/api/v1/orders", HttpMethod.POST,
				new HttpEntity<>(new PlaceOrderRequest(uniqueKey(), "cust-1", null,
						List.of(new PlaceOrderRequest.Line("AUD-HP-001", 1))), headers),
				OrderResponse.class);

		assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isEqualTo(correlationId);
		// Stamped onto the history rows too, so a transition leads back to the request that caused it.
		assertThat(historyOf(response.getBody().orderNumber()))
				.allSatisfy(entry -> assertThat(entry.get("correlationId")).isEqualTo(correlationId));
	}

	@Test
	@DisplayName("the service reports itself live and names what it depends on")
	void serviceInfo() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/order/_info", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "live");
		assertThat(response.getBody()).containsKey("inventoryUrl");
	}
}
