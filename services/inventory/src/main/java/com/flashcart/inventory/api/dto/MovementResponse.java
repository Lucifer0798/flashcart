package com.flashcart.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.inventory.domain.MovementType;
import com.flashcart.inventory.domain.StockMovement;

/**
 * @param onHandDelta   signed change this movement applied to on-hand
 * @param reservedDelta signed change this movement applied to reserved
 * @param correlationId the request that caused it, so a ledger entry leads straight back to a log
 */
public record MovementResponse(
		UUID id,
		String sku,
		MovementType type,
		int onHandDelta,
		int reservedDelta,
		UUID reservationId,
		UUID flashSaleId,
		String reason,
		String correlationId,
		Instant createdAt) {

	public static MovementResponse from(StockMovement movement) {
		return new MovementResponse(movement.getId(), movement.getSku(), movement.getType(),
				movement.getOnHandDelta(), movement.getReservedDelta(), movement.getReservationId(),
				movement.getFlashSaleId(), movement.getReason(), movement.getCorrelationId(),
				movement.getCreatedAt());
	}
}
