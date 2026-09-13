package com.flashcart.shipping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.message.ShipmentCreated;
import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.domain.ShipmentStatus;
import com.flashcart.shipping.service.ShipmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import com.flashcart.common.security.AccessTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping against a real PostgreSQL, with the bus captured.
 *
 * <p>The property that matters most here is idempotency, and it matters more than anywhere else in
 * the platform: a duplicated shipment is a second parcel of real goods leaving a real warehouse, and
 * no compensating event brings that back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingEventPublisher.class)
@TestPropertySource(properties = {
		"spring.kafka.listener.auto-startup=false",
		// This suite records what the service decides, so its own publisher must win. Turning the
		// queue off leaves the consumer-side dedup beans in place, which the listeners still need.
		"flashcart.outbox.enabled=false",
})
class ShippingIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	private ShipmentService shipments;

	@Autowired
	private RecordingEventPublisher.Recorder events;

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private AccessTokens tokens;

	@Autowired
	private JdbcTemplate jdbc;

	/**
	 * Signs every request as an operator: dispatching and delivering a parcel are warehouse actions,
	 * not shopper ones (ADR 0022).
	 */
	@BeforeEach
	void signInAsOperator() {
		String token = tokens.issue("ops-test", "ops@example.test", List.of(AccessTokens.OPERATOR));
		rest.getRestTemplate().getInterceptors().clear();
		rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
			// Only when the caller has not said who it is. The ownership tests below act as a
			// specific shopper, and an interceptor that overwrote that would quietly turn every one
			// of them into another operator test that passes for the wrong reason.
			if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
				request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			}
			return execution.execute(request, body);
		});
	}

	@BeforeEach
	void reset() {
		events.clear();
	}

	private Shipment create(UUID orderId, String orderNumber) {
		return shipments.create(orderId, orderNumber, "cust-1",
				List.of(new ShipmentService.RequestedLine("AUD-HP-001", 1)));
	}

	@Test
	@DisplayName("a shipment is booked with a tracking number and announced")
	void createsAndAnnounces() {
		UUID orderId = UUID.randomUUID();

		Shipment shipment = create(orderId, "FC-SHIP0001");

		assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
		assertThat(shipment.getTrackingNumber()).startsWith("FCL");
		assertThat(shipment.getLines()).singleElement()
				.satisfies(line -> assertThat(line.getSku()).isEqualTo("AUD-HP-001"));

		ShipmentCreated event = events.require(ShipmentCreated.class);
		assertThat(event.trackingNumber()).isEqualTo(shipment.getTrackingNumber());
		assertThat(event.aggregateId()).isEqualTo(orderId.toString());
	}

	@Test
	@DisplayName("a redelivered command returns the same shipment rather than booking a second parcel")
	void createIsIdempotent() {
		UUID orderId = UUID.randomUUID();

		Shipment first = create(orderId, "FC-SHIP0002");
		events.clear();
		Shipment retry = create(orderId, "FC-SHIP0002");

		// One consignment. The unique constraint on order_id is what enforces this, not a check —
		// two commands arriving at once would both pass a check.
		assertThat(retry.getId()).isEqualTo(first.getId());
		assertThat(retry.getTrackingNumber()).isEqualTo(first.getTrackingNumber());

		// Re-announced, because a duplicate command usually means the first event went missing and
		// the order would otherwise sit in FULFILLING with a shipment sitting right there.
		assertThat(events.require(ShipmentCreated.class).trackingNumber())
				.isEqualTo(first.getTrackingNumber());
	}

	@Test
	@DisplayName("dispatch and delivery walk the shipment forward, and both are idempotent")
	void dispatchThenDeliver() {
		Shipment shipment = create(UUID.randomUUID(), "FC-SHIP0003");
		String tracking = shipment.getTrackingNumber();

		assertThat(shipments.dispatch(tracking).getStatus()).isEqualTo(ShipmentStatus.DISPATCHED);
		assertThat(shipments.dispatch(tracking).getStatus()).isEqualTo(ShipmentStatus.DISPATCHED);

		assertThat(shipments.deliver(tracking).getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
		assertThat(shipments.deliver(tracking).getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
		assertThat(shipments.getByTracking(tracking).getDeliveredAt()).isNotNull();
	}

	@Test
	@DisplayName("delivery before dispatch is refused")
	void cannotDeliverBeforeDispatch() {
		Shipment shipment = create(UUID.randomUUID(), "FC-SHIP0004");

		ResponseEntity<Map> response = rest.postForEntity(
				"/api/v1/shipments/" + shipment.getTrackingNumber() + "/deliver", null, Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "SHIPMENT_NOT_DELIVERABLE");
	}

	@Test
	@DisplayName("a shipment is trackable over HTTP by tracking number and by order")
	void trackable() {
		Shipment shipment = create(UUID.randomUUID(), "FC-SHIP0005");

		ResponseEntity<Map> byTracking = rest.getForEntity(
				"/api/v1/shipments/" + shipment.getTrackingNumber(), Map.class);
		ResponseEntity<Map> byOrder = rest.getForEntity(
				"/api/v1/shipments/order/FC-SHIP0005", Map.class);

		assertThat(byTracking.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(byOrder.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(byOrder.getBody()).containsEntry("trackingNumber", shipment.getTrackingNumber());
	}

	@Test
	@DisplayName("an unknown shipment is a 404 in the shared envelope")
	void unknownShipmentIsNotFound() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/shipments/FCL0000000000", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
	}

	@Test
	@DisplayName("there is no endpoint that creates a shipment")
	void shipmentsAreOnlyCreatedByCommand() {
		// A shipment exists only once payment has settled, and the only thing that knows that is the
		// order saga. An HTTP create would be a way to ship goods for money that never arrived.
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/shipments",
				Map.of("orderNumber", "FC-SNEAKY1"), Map.class);

		assertThat(response.getStatusCode())
				.isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
	}

	// --- whose parcel it is (ADR 0023) ----------------------------------------------------------------

	private HttpHeaders bearer(String customerId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION,
				"Bearer " + tokens.issue(customerId, customerId + "@example.test"));
		return headers;
	}

	/** A request as a specific shopper, overriding the suite's operator interceptor. */
	private ResponseEntity<Map> as(String customerId, String path) {
		return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(customerId)), Map.class);
	}

	private Shipment createFor(String customerId, String orderNumber) {
		return shipments.create(UUID.randomUUID(), orderNumber, customerId,
				List.of(new ShipmentService.RequestedLine("AUD-HP-001", 1)));
	}

	@Test
	@DisplayName("a shopper can track their own parcel")
	void shopperTracksTheirOwn() {
		Shipment shipment = createFor("shopper-a", "FC-MINE001");

		ResponseEntity<Map> response = as("shopper-a", "/api/v1/shipments/" + shipment.getTrackingNumber());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("orderNumber", "FC-MINE001");
	}

	@Test
	@DisplayName("and by their own order number")
	void shopperReadsByOrderNumber() {
		createFor("shopper-a", "FC-MINE002");

		ResponseEntity<Map> response = as("shopper-a", "/api/v1/shipments/order/FC-MINE002");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("somebody else's tracking number is 404 -- it is the identifier printed on a label")
	void anothersTrackingIsNotFound() {
		Shipment shipment = createFor("shopper-a", "FC-THEIRS1");

		ResponseEntity<Map> response = as("shopper-b", "/api/v1/shipments/" + shipment.getTrackingNumber());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
	}

	@Test
	@DisplayName("and somebody else's order number is 404 too")
	void anothersOrderNumberIsNotFound() {
		createFor("shopper-a", "FC-THEIRS2");

		ResponseEntity<Map> response = as("shopper-b", "/api/v1/shipments/order/FC-THEIRS2");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("a listing returns the caller's own shipments and nobody else's")
	void listingIsScopedToTheCaller() {
		// Two lines deliberately. Fetching the collection to avoid a lazy-load blowup means a join,
		// and a join against a bag is how one shipment starts being returned twice.
		shipments.create(UUID.randomUUID(), "FC-LIST001", "shopper-c",
				List.of(new ShipmentService.RequestedLine("AUD-HP-001", 1),
						new ShipmentService.RequestedLine("AUD-HP-002", 2)));
		createFor("shopper-d", "FC-LIST002");

		ResponseEntity<List> response = rest.exchange("/api/v1/shipments", HttpMethod.GET,
				new HttpEntity<>(bearer("shopper-c")), List.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().get(0).toString()).contains("FC-LIST001");
	}

	@Test
	@DisplayName("asking for another customer's listing is refused, not quietly answered with your own")
	void askingForAnothersListingIsForbidden() {
		ResponseEntity<Map> response = as("shopper-e", "/api/v1/shipments?customerId=shopper-f");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).containsEntry("code", "OPERATOR_REQUIRED");
	}

	@Test
	@DisplayName("a customer may watch a parcel move, not move it: dispatch stays the warehouse's")
	void shopperCannotDispatch() {
		Shipment shipment = createFor("shopper-g", "FC-NODISP1");

		ResponseEntity<Map> response = rest.exchange(
				"/api/v1/shipments/" + shipment.getTrackingNumber() + "/dispatch",
				HttpMethod.POST, new HttpEntity<>(bearer("shopper-g")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("nor mark their own parcel delivered, which would be editing the warehouse's records")
	void shopperCannotDeliver() {
		Shipment shipment = createFor("shopper-h", "FC-NODELV1");

		ResponseEntity<Map> response = rest.exchange(
				"/api/v1/shipments/" + shipment.getTrackingNumber() + "/deliver",
				HttpMethod.POST, new HttpEntity<>(bearer("shopper-h")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an operator still reads and still dispatches")
	void operatorIsUnaffected() {
		Shipment shipment = createFor("shopper-i", "FC-OPSREAD");

		// No explicit header, so the suite's operator interceptor supplies one.
		assertThat(rest.getForEntity("/api/v1/shipments/order/FC-OPSREAD", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(rest.postForEntity("/api/v1/shipments/" + shipment.getTrackingNumber() + "/dispatch",
				null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("an unusable token is still 401: opening this to customers did not open it to nobody")
	void anonymousIsStillRefused() {
		HttpHeaders none = new HttpHeaders();
		none.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token");
		ResponseEntity<Map> response = rest.exchange("/api/v1/shipments", HttpMethod.GET,
				new HttpEntity<>(none), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// --- what an operator read is recorded (ADR 0025) -------------------------------------------------

	private Long auditRows(String customerId) {
		return jdbc.queryForObject(
				"select count(*) from operator_access_log where customer_id = ?", Long.class, customerId);
	}

	@Test
	@DisplayName("an operator tracking somebody else's parcel leaves a record of who looked")
	void operatorReadIsRecorded() {
		createFor("audit-owner-a", "FC-AUDIT01");

		// No explicit header, so the suite's operator interceptor supplies one.
		assertThat(rest.getForEntity("/api/v1/shipments/order/FC-AUDIT01", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-a'");
		assertThat(row).containsEntry("action", "READ_SHIPMENT");
		assertThat(row).containsEntry("resource_id", "FC-AUDIT01");
	}

	@Test
	@DisplayName("a customer tracking their own parcel leaves nothing")
	void ownReadIsNotRecorded() {
		createFor("audit-owner-b", "FC-AUDIT02");

		assertThat(as("audit-owner-b", "/api/v1/shipments/order/FC-AUDIT02").getStatusCode())
				.isEqualTo(HttpStatus.OK);

		assertThat(auditRows("audit-owner-b")).isZero();
	}

	@Test
	@DisplayName("nor does a refused read: nothing was disclosed, so there is nothing to record")
	void refusedReadIsNotRecorded() {
		createFor("audit-owner-c", "FC-AUDIT03");

		assertThat(as("audit-stranger", "/api/v1/shipments/order/FC-AUDIT03").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(auditRows("audit-owner-c")).isZero();
	}

	@Test
	@DisplayName("a tracking-number lookup by an operator is recorded under the number they used")
	void operatorTrackingLookupIsRecorded() {
		Shipment shipment = createFor("audit-owner-d", "FC-AUDIT04");

		assertThat(rest.getForEntity("/api/v1/shipments/" + shipment.getTrackingNumber(), Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-d'");
		assertThat(row).containsEntry("resource_id", shipment.getTrackingNumber());
	}

	@Test
	@DisplayName("if the access cannot be recorded the parcel is not shown")
	void anUnrecordableReadIsRefused() {
		createFor("audit-owner-e", "FC-AUDIT05");

		// Renamed rather than dropped so the suite continues afterwards.
		jdbc.execute("alter table operator_access_log rename to operator_access_log_hidden");
		try {
			ResponseEntity<Map> response = rest.getForEntity("/api/v1/shipments/order/FC-AUDIT05", Map.class);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {
			jdbc.execute("alter table operator_access_log_hidden rename to operator_access_log");
		}
	}

	@Test
	@DisplayName("dispatch is unaffected: it is an operator action, not a read of somebody's data")
	void dispatchIsNotAudited() {
		Shipment shipment = createFor("audit-owner-g", "FC-AUDIT07");

		assertThat(rest.postForEntity("/api/v1/shipments/" + shipment.getTrackingNumber() + "/dispatch",
				null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Worth stating: this table answers "who read whose data", not "what did the warehouse do".
		// Conflating the two would make it useless for both questions.
		assertThat(auditRows("audit-owner-g")).isZero();
	}
}
