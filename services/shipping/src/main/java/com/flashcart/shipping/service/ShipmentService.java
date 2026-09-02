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

	@Transactional
	public Shipment dispatch(String trackingNumber) {
		Shipment shipment = requireByTracking(trackingNumber);
		if (shipment.getStatus() == ShipmentStatus.DISPATCHED) {
			return shipment;
		}
		if (shipment.getStatus() != ShipmentStatus.CREATED) {
			throw new ConflictException("SHIPMENT_NOT_DISPATCHABLE",
					"Shipment %s is %s".formatted(trackingNumber, shipment.getStatus()));
		}
		shipment.dispatch(clock.instant());
		return shipment;
	}

	@Transactional
	public Shipment deliver(String trackingNumber) {
		Shipment shipment = requireByTracking(trackingNumber);
		if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
			return shipment;
		}
		if (shipment.getStatus() != ShipmentStatus.DISPATCHED) {
			throw new ConflictException("SHIPMENT_NOT_DELIVERABLE",
					"Shipment %s is %s and has not been dispatched".formatted(trackingNumber,
							shipment.getStatus()));
		}
		shipment.deliver(clock.instant());
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
