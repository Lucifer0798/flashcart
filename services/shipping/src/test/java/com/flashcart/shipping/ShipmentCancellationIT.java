package com.flashcart.shipping;

import java.util.UUID;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.event.message.ShipmentCancellationRefused;
import com.flashcart.common.event.message.ShipmentCancelled;
import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.domain.ShipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stopping a consignment, and refusing to.
 *
 * <p>Shipping is the only service that can answer whether a paid order may still be cancelled,
 * because the answer is a fact about the physical world rather than about any state machine. These
 * are the two answers, and the refusal is the one worth being careful with: a refusal that is not
 * published leaves the order waiting in {@code CANCELLATION_REQUESTED} for a reply that never comes.
 */
class ShipmentCancellationIT extends AbstractShippingIT {

	@Test
	@DisplayName("a consignment still in the warehouse is stopped")
	void createdShipmentIsCancelled() {
		Shipment shipment = create(UUID.randomUUID(), "FC-CANCEL-1");
		events.clear();

		Shipment cancelled = shipments.cancel("FC-CANCEL-1", "customer cancelled");

		assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
		assertThat(cancelled.getCancelledAt()).isNotNull();

		ShipmentCancelled event = events.require(ShipmentCancelled.class);
		assertThat(event.trackingNumber()).isEqualTo(shipment.getTrackingNumber());
		assertThat(events.published(ShipmentCancellationRefused.class)).isFalse();
	}

	@Test
	@DisplayName("a dispatched consignment is not stopped, and the refusal is published")
	void dispatchedShipmentIsRefused() {
		Shipment shipment = create(UUID.randomUUID(), "FC-CANCEL-2");
		shipments.dispatch(shipment.getTrackingNumber());
		events.clear();

		Shipment after = shipments.cancel("FC-CANCEL-2", "customer cancelled");

		// The status is untouched. A parcel with the carrier is a physical object somewhere else, and
		// the one thing this must never do is let a column claim otherwise.
		assertThat(after.getStatus()).isEqualTo(ShipmentStatus.DISPATCHED);
		assertThat(after.getCancelledAt()).isNull();

		ShipmentCancellationRefused refused = events.require(ShipmentCancellationRefused.class);
		// The status travels with the refusal so the order's history records why it failed rather
		// than merely that it did -- which is what somebody answering "I cancelled this and it
		// arrived anyway" needs.
		assertThat(refused.shipmentStatus()).isEqualTo("DISPATCHED");
		assertThat(events.published(ShipmentCancelled.class)).isFalse();
	}

	@Test
	@DisplayName("a delivered consignment is refused too")
	void deliveredShipmentIsRefused() {
		Shipment shipment = create(UUID.randomUUID(), "FC-CANCEL-3");
		shipments.dispatch(shipment.getTrackingNumber());
		shipments.deliver(shipment.getTrackingNumber());
		events.clear();

		shipments.cancel("FC-CANCEL-3", "customer cancelled");

		assertThat(events.require(ShipmentCancellationRefused.class).shipmentStatus())
				.isEqualTo("DELIVERED");
	}

	@Test
	@DisplayName("a repeated cancellation does not cancel twice, and says so again")
	void cancellationIsIdempotent() {
		create(UUID.randomUUID(), "FC-CANCEL-4");
		shipments.cancel("FC-CANCEL-4", "customer cancelled");
		events.clear();

		Shipment again = shipments.cancel("FC-CANCEL-4", "customer cancelled");

		assertThat(again.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
		// Re-published rather than ignored: a repeated command most often means the first answer was
		// what went missing, and silence would leave the order stuck.
		assertThat(events.countOf(ShipmentCancelled.class)).isEqualTo(1);
		assertThat(events.published(ShipmentCancellationRefused.class)).isFalse();
	}

	@Test
	@DisplayName("a cancelled consignment cannot then be dispatched")
	void cancelledShipmentIsNotDispatchable() {
		Shipment shipment = create(UUID.randomUUID(), "FC-CANCEL-5");
		shipments.cancel("FC-CANCEL-5", "customer cancelled");

		// The other half of the race. Cancelling then dispatching would send goods for an order the
		// platform has already refunded, and the warehouse endpoint has to refuse it on its own
		// rather than relying on nobody trying.
		assertThatThrownBy(() -> shipments.dispatch(shipment.getTrackingNumber()))
				.isInstanceOf(ConflictException.class);
	}
}
