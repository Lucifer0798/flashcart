package com.flashcart.shipping;

import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.message.ShipmentCreated;
import com.flashcart.common.event.message.ShipmentDelivered;
import com.flashcart.common.event.message.ShipmentDispatched;
import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the shipping service decides, against a real PostgreSQL with the bus captured.
 *
 * <p>The property that matters most here is idempotency, and it matters more than anywhere else in
 * the platform: a duplicated shipment is a second parcel of real goods leaving a real warehouse, and
 * no compensating event brings that back.
 *
 * <p>Whose parcel it is lives in {@link ShipmentAccessIT}; what is recorded when an operator looks at
 * somebody else's is {@link OperatorAccessLogIT}.
 */
class ShippingIT extends AbstractShippingIT {

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
	@DisplayName("dispatch is announced, and re-announced on a repeat scan")
	void dispatchIsPublished() {
		Shipment shipment = create(UUID.randomUUID(), "FC-SHIP0011");
		String tracking = shipment.getTrackingNumber();
		events.clear();

		shipments.dispatch(tracking);

		// The last transition here that used to happen in silence. It is the moment cancelling
		// becomes impossible, and the order service had no way to know it had passed.
		assertThat(events.require(ShipmentDispatched.class).trackingNumber()).isEqualTo(tracking);

		events.clear();
		shipments.dispatch(tracking);
		ShipmentDispatched again = events.require(ShipmentDispatched.class);
		// Both sides round-tripped through PostgreSQL by now, so this is an exact comparison that
		// can fail -- a fresh clock reading here would not match the row.
		assertThat(again.dispatchedAt()).isEqualTo(shipments.getByTracking(tracking).getDispatchedAt());
	}

	@Test
	@DisplayName("delivery is announced, which is what lets an order finish")
	void deliveryIsPublished() {
		Shipment shipment = create(UUID.randomUUID(), "FC-SHIP0009");
		String tracking = shipment.getTrackingNumber();
		shipments.dispatch(tracking);
		events.clear();

		shipments.deliver(tracking);

		// The event that did not exist. Without it OrderStatus.DELIVERED was unreachable and every
		// shipped order stayed SHIPPED forever, while the README drew delivery as the happy ending.
		ShipmentDelivered event = events.require(ShipmentDelivered.class);
		assertThat(event.trackingNumber()).isEqualTo(tracking);
		assertThat(event.orderNumber()).isEqualTo("FC-SHIP0009");
		assertThat(event.deliveredAt()).isNotNull();
	}

	@Test
	@DisplayName("scanning a delivered parcel again re-announces it rather than saying nothing")
	void deliveryIsRepublished() {
		Shipment shipment = create(UUID.randomUUID(), "FC-SHIP0010");
		String tracking = shipment.getTrackingNumber();
		shipments.dispatch(tracking);
		shipments.deliver(tracking);
		events.clear();

		shipments.deliver(tracking);

		// An operator scanning twice is far likelier than an order content to sit in SHIPPED, so a
		// repeat is treated as evidence the first event was lost -- as create() already does.
		ShipmentDelivered event = events.require(ShipmentDelivered.class);

		// And this is where the provenance claim is pinned, rather than on the first publish. The
		// event has to carry the time the parcel arrived, not the time it was talked about -- but on
		// the first publish both sides are within microseconds of each other, so any assertion there
		// passes whether the value came from the row or from a fresh clock reading. Here the entity
		// has been round-tripped through PostgreSQL, so the stored value is the only thing that can
		// produce an exact match, and a re-read of the clock would fail this.
		assertThat(event.deliveredAt()).isEqualTo(shipments.getByTracking(tracking).getDeliveredAt());
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
