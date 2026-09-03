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
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
}
