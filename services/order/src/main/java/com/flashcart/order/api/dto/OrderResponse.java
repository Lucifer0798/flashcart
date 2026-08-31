package com.flashcart.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.order.OrderStateMachine;
import com.flashcart.common.order.OrderStatus;
import com.flashcart.order.domain.Order;

/**
 * @param status               where the order is now
 * @param allowedNextStates    what could legally happen next, straight from the state machine.
 *                             Exposed so a client can render the right controls instead of hard
 *                             coding a copy of the rules that will drift from this service's.
 * @param reservationExpiresAt when the hold lapses — clients render a countdown from this
 */
public record OrderResponse(
		UUID id,
		String orderNumber,
		String customerId,
		OrderStatus status,
		List<OrderStatus> allowedNextStates,
		UUID flashSaleId,
		String currency,
		BigDecimal subtotal,
		BigDecimal total,
		Instant reservationExpiresAt,
		String cancellationReason,
		List<Line> lines,
		Long version,
		Instant createdAt,
		Instant updatedAt) {

	public record Line(String sku, String productName, int quantity, BigDecimal unitPrice,
			BigDecimal lineTotal) {
	}

	public static OrderResponse from(Order order) {
		List<Line> lines = order.getLines().stream()
				.map(line -> new Line(line.getSku(), line.getProductName(), line.getQuantity(),
						line.getUnitPrice(), line.getLineTotal()))
				.toList();

		return new OrderResponse(
				order.getId(),
				order.getOrderNumber(),
				order.getCustomerId(),
				order.getStatus(),
				List.copyOf(OrderStateMachine.nextStates(order.getStatus())),
				order.getFlashSaleId(),
				order.getCurrency(),
				order.getSubtotal(),
				order.getTotal(),
				order.getReservationExpiresAt(),
				order.getCancellationReason(),
				lines,
				order.getVersion(),
				order.getCreatedAt(),
				order.getUpdatedAt());
	}
}
