package com.flashcart.inventory.service;

import java.util.UUID;

import com.flashcart.common.web.CorrelationId;
import com.flashcart.inventory.domain.MovementType;
import com.flashcart.inventory.domain.StockMovement;
import com.flashcart.inventory.repository.StockMovementRepository;
import org.springframework.stereotype.Component;

/**
 * Writes the ledger.
 *
 * <p>Its own component so that every path which moves a number goes through one place, and so the
 * correlation id gets attached without every caller remembering to. A movement written without the
 * balance change, or a balance change without the movement, would make the ledger a lie — both
 * always happen inside the caller's transaction.
 */
@Component
public class MovementRecorder {

	private final StockMovementRepository movements;

	public MovementRecorder(StockMovementRepository movements) {
		this.movements = movements;
	}

	public void record(String sku, MovementType type, int onHandDelta, int reservedDelta,
			UUID reservationId, UUID flashSaleId, String reason) {
		movements.save(new StockMovement(UUID.randomUUID(), sku, type, onHandDelta, reservedDelta,
				reservationId, flashSaleId, reason, CorrelationId.current()));
	}

	public void reserved(String sku, int quantity, UUID reservationId, UUID flashSaleId) {
		record(sku, MovementType.RESERVED, 0, quantity, reservationId, flashSaleId, null);
	}

	public void released(String sku, int quantity, UUID reservationId, UUID flashSaleId, String reason) {
		record(sku, MovementType.RELEASED, 0, -quantity, reservationId, flashSaleId, reason);
	}

	public void expired(String sku, int quantity, UUID reservationId, UUID flashSaleId) {
		record(sku, MovementType.EXPIRED, 0, -quantity, reservationId, flashSaleId, "reservation TTL elapsed");
	}

	public void committed(String sku, int quantity, UUID reservationId, UUID flashSaleId) {
		record(sku, MovementType.COMMITTED, -quantity, -quantity, reservationId, flashSaleId, null);
	}
}
