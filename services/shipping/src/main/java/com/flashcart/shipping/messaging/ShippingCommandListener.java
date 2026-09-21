package com.flashcart.shipping.messaging;

import java.util.UUID;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.CancelShipment;
import com.flashcart.common.event.message.CreateShipment;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.shipping.service.ShipmentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Books a consignment when the order service says payment has settled, and stops one on request. */
@Component
public class ShippingCommandListener {

	/** Names this consumer in {@code processed_events}. */
	private static final String CONSUMER = "shipping-commands";

	private final ShipmentService shipments;
	private final IdempotentHandler handler;

	public ShippingCommandListener(ShipmentService shipments, IdempotentHandler handler) {
		this.shipments = shipments;
		this.handler = handler;
	}

	@KafkaListener(topics = Topics.SHIPPING_COMMANDS, containerFactory = "createShipmentFactory",
			groupId = ShippingKafkaConfig.GROUP)
	public void onCreateShipment(CreateShipment command) {
		// A duplicated shipment is a second parcel of real goods leaving a real warehouse, and no
		// compensating event brings that back. The unique constraint on order_id already prevents
		// it; this stops the attempt happening at all.
		handler.handle(command, CONSUMER, () -> shipments.create(
				UUID.fromString(command.aggregateId()),
				command.orderNumber(),
				command.customerId(),
				command.lines().stream()
						.map(line -> new ShipmentService.RequestedLine(line.sku(), line.quantity()))
						.toList()));
	}

	/**
	 * Its own consumer group. Two listeners sharing one would split the partitions between them and
	 * each would filter away — and acknowledge — most of its own messages.
	 *
	 * <p>The group also makes the ordering question moot. A {@code CancelShipment} can only be sent
	 * for an order the saga has already seen shipped, which means {@code CreateShipment} was consumed
	 * and answered long before this arrives; there is no race in which a cancellation reaches a
	 * shipment that does not exist yet.
	 */
	@KafkaListener(topics = Topics.SHIPPING_COMMANDS, containerFactory = "cancelShipmentFactory",
			groupId = ShippingKafkaConfig.GROUP + "-cancel")
	public void onCancelShipment(CancelShipment command) {
		handler.handle(command, CONSUMER, () ->
				shipments.cancel(command.orderNumber(), command.reason()));
	}
}
