package com.flashcart.shipping.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.domain.ShipmentStatus;

public record ShipmentResponse(
		UUID id,
		UUID orderId,
		String orderNumber,
		String customerId,
		ShipmentStatus status,
		String carrier,
		String trackingNumber,
		Instant dispatchedAt,
		Instant deliveredAt,
		List<Line> lines,
		Instant createdAt) {

	public record Line(String sku, int quantity) {
	}

	public static ShipmentResponse from(Shipment shipment) {
		return new ShipmentResponse(shipment.getId(), shipment.getOrderId(), shipment.getOrderNumber(),
				shipment.getCustomerId(), shipment.getStatus(), shipment.getCarrier(),
				shipment.getTrackingNumber(), shipment.getDispatchedAt(), shipment.getDeliveredAt(),
				shipment.getLines().stream().map(line -> new Line(line.getSku(), line.getQuantity()))
						.toList(),
				shipment.getCreatedAt());
	}
}
