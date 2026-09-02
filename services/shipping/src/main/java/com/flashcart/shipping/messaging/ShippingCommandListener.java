package com.flashcart.shipping.messaging;

import java.util.UUID;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.CreateShipment;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.shipping.service.ShipmentService;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Books a consignment when the order service says payment has settled. */
@Component
public class ShippingCommandListener {

	private final ShipmentService shipments;

	public ShippingCommandListener(ShipmentService shipments) {
		this.shipments = shipments;
	}

	@KafkaListener(topics = Topics.SHIPPING_COMMANDS, containerFactory = "createShipmentFactory",
			groupId = ShippingKafkaConfig.GROUP)
	public void onCreateShipment(CreateShipment command) {
		if (command.correlationId() != null) {
			MDC.put(CorrelationId.MDC_KEY, command.correlationId());
		}
		try {
			shipments.create(
					UUID.fromString(command.aggregateId()),
					command.orderNumber(),
					command.customerId(),
					command.lines().stream()
							.map(line -> new ShipmentService.RequestedLine(line.sku(), line.quantity()))
							.toList());
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}
}
