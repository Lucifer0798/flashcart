package com.flashcart.order.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.order.domain.OrderStatusChange;

/**
 * One transition, as it happened.
 *
 * @param fromStatus null on the first entry, where the order came into existence
 */
public record OrderHistoryResponse(
		UUID id,
		OrderStatus fromStatus,
		OrderStatus toStatus,
		String reason,
		String correlationId,
		Instant createdAt) {

	public static OrderHistoryResponse from(OrderStatusChange change) {
		return new OrderHistoryResponse(change.getId(), change.getFromStatus(), change.getToStatus(),
				change.getReason(), change.getCorrelationId(), change.getCreatedAt());
	}
}
