package com.flashcart.shipping.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.ShipmentCancellationRefused;
import com.flashcart.common.event.message.ShipmentCancelled;
import com.flashcart.common.event.message.ShipmentDelivered;
import com.flashcart.common.event.message.ShipmentDispatched;
import com.flashcart.common.event.message.ShipmentCreated;
import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.domain.ShipmentLine;
import com.flashcart.shipping.domain.ShipmentStatus;
import com.flashcart.shipping.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books consignments.
 *
 * <p>The interesting property is idempotency, and it is enforced by the database rather than by
 * checking first: {@code shipments.order_id} is unique, so a redelivered {@code CreateShipment}
 * either loses the insert race or finds the existing row. Booking a second consignment for one order
 * means a second parcel of real goods leaving a real warehouse, which is not a mistake that a
 * compensating event can undo.
 */
@Service
public class ShipmentService {

	private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

	private static final String CARRIER = "FlashCart Logistics";

	private final ShipmentRepository shipments;
	private final EventPublisher events;
	private final Clock clock;

	public ShipmentService(ShipmentRepository shipments, EventPublisher events, Clock clock) {
		this.shipments = shipments;
		this.events = events;
		this.clock = clock;
	}

	public record RequestedLine(String sku, int quantity) {
	}

	/**
	 * Create the consignment for an order, or return the one that already exists.
	 *
	 * <p>Publishes {@code ShipmentCreated} either way. If this is a redelivery, the original event is
	 * very likely the thing that went missing — staying silent would leave the order stuck in
	 * {@code FULFILLING} with a shipment sitting right there.
	 */
	@Transactional
	public Shipment create(UUID orderId, String orderNumber, String customerId,
			List<RequestedLine> lines) {

		Shipment existing = shipments.findByOrderId(orderId).orElse(null);
		if (existing != null) {
			log.info("Shipment for order {} already exists; re-publishing its creation", orderNumber);
			publishCreated(existing);
			return existing;
		}

		Shipment shipment = new Shipment(UUID.randomUUID(), orderId, orderNumber, customerId, CARRIER,
				nextTrackingNumber());
		for (RequestedLine line : lines) {
			shipment.addLine(new ShipmentLine(UUID.randomUUID(), line.sku(), line.quantity()));
		}

		try {
			shipments.saveAndFlush(shipment);
		}
		catch (DataIntegrityViolationException ex) {
			// Two commands for one order arrived at once. The unique constraint is the real defence;
			// the loser reports the winner's shipment.
			Shipment winner = shipments.findByOrderId(orderId).orElseThrow();
			publishCreated(winner);
			return winner;
		}

		publishCreated(shipment);
		return shipment;
	}

	/**
	 * Stop the consignment for an order, if it has not gone yet.
	 *
	 * <p>This is the only place in the platform that answers a question rather than carrying out an
	 * instruction, and it is here because it is the only service that knows. The order service cannot
	 * decide whether a cancellation is possible; the parcel either left or it did not, and that fact
	 * lives in this table.
	 *
	 * <p>Both answers are published as events, which is the part worth insisting on. A refusal that
	 * only logged would leave the order stuck in {@code CANCELLATION_REQUESTED} forever, waiting for a
	 * reply that was never going to come — the failure mode being silent is precisely what makes a
	 * saga hard to debug.
	 *
	 * <p>A dispatch racing a cancellation is settled by the row's {@code @Version}: both transactions
	 * read {@code CREATED}, one commits, and the other fails its optimistic lock. Because the loser is
	 * this consumer, it is retried, reads {@code DISPATCHED} and refuses — which is the right answer,
	 * arrived at by the database rather than by whichever message happened to be quicker.
	 */
	@Transactional
	public Shipment cancel(String orderNumber, String reason) {
		Shipment shipment = shipments.findByOrderNumber(orderNumber)
				.orElseThrow(() -> ResourceNotFoundException.of("Shipment for order", orderNumber));

		if (shipment.getStatus() == ShipmentStatus.CANCELLED) {
			// A redelivery. As everywhere else here, the lost message is likelier to have been the
			// answer than the question, so the answer is sent again.
			log.info("Shipment {} is already cancelled; re-publishing", shipment.getTrackingNumber());
			publishCancelled(shipment);
			return shipment;
		}
		if (shipment.getStatus() != ShipmentStatus.CREATED) {
			log.info("Refusing to cancel shipment {} for order {}: it is {}",
					shipment.getTrackingNumber(), orderNumber, shipment.getStatus());
			events.publish(Topics.SHIPPING_EVENTS, new ShipmentCancellationRefused(
					EventMetadata.of(ShipmentCancellationRefused.TYPE, shipment.getOrderId()),
					shipment.getId().toString(), orderNumber, shipment.getStatus().name(),
					"the consignment has already been " + shipment.getStatus().name().toLowerCase()));
			return shipment;
		}

		shipment.cancel(clock.instant());
		log.info("Cancelled shipment {} for order {} ({})", shipment.getTrackingNumber(), orderNumber,
				reason);
		publishCancelled(shipment);
		return shipment;
	}

	/**
	 * The consignment goes to the carrier, and the order is told.
	 *
	 * <p>The last transition here that used to happen in silence. It is the moment cancelling becomes
	 * impossible, and until ADR 0033 the order service had no way to know it had passed: it could only
	 * ask, and wait to be refused.
	 *
	 * <p>Re-publishes on a repeat call, as {@link #create} and {@link #deliver} do.
	 */
	@Transactional
	public Shipment dispatch(String trackingNumber) {
		Shipment shipment = requireByTracking(trackingNumber);
		if (shipment.getStatus() == ShipmentStatus.DISPATCHED) {
			log.info("Shipment {} is already dispatched; re-publishing", trackingNumber);
			publishDispatched(shipment);
			return shipment;
		}
		if (shipment.getStatus() != ShipmentStatus.CREATED) {
			throw new ConflictException("SHIPMENT_NOT_DISPATCHABLE",
					"Shipment %s is %s".formatted(trackingNumber, shipment.getStatus()));
		}
		shipment.dispatch(clock.instant());
		publishDispatched(shipment);
		return shipment;
	}

	/**
	 * The parcel arrived, and the order is told so.
	 *
	 * <p>Publishing is the whole of what changed here. This method has always set its own status and
	 * said nothing, which left {@code OrderStatus.DELIVERED} unreachable: no order in the history of
	 * this platform had ever finished, though the README and the architecture diagram both drew it as
	 * the end of the happy path.
	 *
	 * <p>Re-publishes when called again on a delivered parcel, for the reason {@link #create} does.
	 * The repeat almost always means the first event was what went missing, and an operator scanning
	 * a parcel twice is a great deal more likely than an order that is content to sit in
	 * {@code SHIPPED} forever.
	 */
	@Transactional
	public Shipment deliver(String trackingNumber) {
		Shipment shipment = requireByTracking(trackingNumber);
		if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
			log.info("Shipment {} is already delivered; re-publishing", trackingNumber);
			publishDelivered(shipment);
			return shipment;
		}
		if (shipment.getStatus() != ShipmentStatus.DISPATCHED) {
			throw new ConflictException("SHIPMENT_NOT_DELIVERABLE",
					"Shipment %s is %s and has not been dispatched".formatted(trackingNumber,
							shipment.getStatus()));
		}
		shipment.deliver(clock.instant());
		publishDelivered(shipment);
		return shipment;
	}

	@Transactional(readOnly = true)
	public Shipment getByOrderNumber(String orderNumber) {
		return shipments.findByOrderNumber(orderNumber)
				.orElseThrow(() -> ResourceNotFoundException.of("Shipment for order", orderNumber));
	}

	@Transactional(readOnly = true)
	public Shipment getByTracking(String trackingNumber) {
		return requireByTracking(trackingNumber);
	}

	@Transactional(readOnly = true)
	public List<Shipment> forCustomer(String customerId) {
		return shipments.findByCustomerIdOrderByCreatedAtDesc(customerId);
	}

	private Shipment requireByTracking(String trackingNumber) {
		return shipments.findByTrackingNumber(trackingNumber)
				.orElseThrow(() -> ResourceNotFoundException.of("Shipment", trackingNumber));
	}

	private void publishDispatched(Shipment shipment) {
		events.publish(Topics.SHIPPING_EVENTS, new ShipmentDispatched(
				EventMetadata.of(ShipmentDispatched.TYPE, shipment.getOrderId()),
				shipment.getId().toString(), shipment.getOrderNumber(), shipment.getTrackingNumber(),
				shipment.getDispatchedAt()));
	}

	private void publishDelivered(Shipment shipment) {
		events.publish(Topics.SHIPPING_EVENTS, new ShipmentDelivered(
				EventMetadata.of(ShipmentDelivered.TYPE, shipment.getOrderId()),
				shipment.getId().toString(), shipment.getOrderNumber(), shipment.getTrackingNumber(),
				shipment.getDeliveredAt()));
	}

	private void publishCancelled(Shipment shipment) {
		events.publish(Topics.SHIPPING_EVENTS, new ShipmentCancelled(
				EventMetadata.of(ShipmentCancelled.TYPE, shipment.getOrderId()),
				shipment.getId().toString(), shipment.getOrderNumber(), shipment.getTrackingNumber()));
	}

	private void publishCreated(Shipment shipment) {
		events.publish(Topics.SHIPPING_EVENTS, new ShipmentCreated(
				EventMetadata.of(ShipmentCreated.TYPE, shipment.getOrderId()),
				shipment.getId().toString(), shipment.getOrderNumber(), shipment.getTrackingNumber(),
				shipment.getCarrier()));
	}

	private static String nextTrackingNumber() {
		StringBuilder builder = new StringBuilder("FCL");
		for (int i = 0; i < 10; i++) {
			builder.append(ThreadLocalRandom.current().nextInt(10));
		}
		return builder.toString();
	}
}
