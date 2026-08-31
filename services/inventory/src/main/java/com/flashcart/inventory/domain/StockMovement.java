package com.flashcart.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * One append-only entry explaining a change to a SKU's position.
 *
 * <p>{@link StockItem} holds the balance; this holds how it got there. The deltas are signed and
 * replay to the balance, so "we are three units short" stops being unanswerable — and every entry
 * carries the correlation id of the request that caused it.
 *
 * <p>Implements {@link Persistable} to state what Spring Data cannot otherwise know: this row is
 * always new. With an application-assigned id and no version column, {@code save()} cannot tell a
 * new entity from a detached one, so it conservatively calls {@code merge()} — a {@code SELECT}
 * before every {@code INSERT}. That is a wasted round trip on the hottest write in the platform,
 * paid once per line on every reservation, release and commit. The table is append-only and its rows
 * are never updated, so {@code isNew()} is unconditionally true and {@code persist()} is always right.
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(nullable = false, length = 64)
	private String sku;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private MovementType type;

	@Column(name = "on_hand_delta", nullable = false)
	private int onHandDelta;

	@Column(name = "reserved_delta", nullable = false)
	private int reservedDelta;

	@Column(name = "reservation_id")
	private UUID reservationId;

	@Column(name = "flash_sale_id")
	private UUID flashSaleId;

	@Column(length = 200)
	private String reason;

	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StockMovement() {
	}

	public StockMovement(UUID id, String sku, MovementType type, int onHandDelta, int reservedDelta,
			UUID reservationId, UUID flashSaleId, String reason, String correlationId) {
		this.id = id;
		this.sku = sku;
		this.type = type;
		this.onHandDelta = onHandDelta;
		this.reservedDelta = reservedDelta;
		this.reservationId = reservationId;
		this.flashSaleId = flashSaleId;
		this.reason = reason;
		this.correlationId = correlationId;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public MovementType getType() {
		return type;
	}

	public int getOnHandDelta() {
		return onHandDelta;
	}

	public int getReservedDelta() {
		return reservedDelta;
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public UUID getFlashSaleId() {
		return flashSaleId;
	}

	public String getReason() {
		return reason;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/**
	 * Unconditionally true. The ledger is append-only — a movement is never edited, only added — so
	 * {@code save()} can always take the {@code persist()} path and skip the {@code SELECT} that
	 * {@code merge()} would issue first.
	 */
	@Override
	public boolean isNew() {
		return true;
	}
}
