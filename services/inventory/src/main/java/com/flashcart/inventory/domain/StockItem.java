package com.flashcart.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The stock position for one SKU.
 *
 * <p>Note what this entity is <em>not</em> used for: the hot reservation path never loads it,
 * mutates it and saves it. That read-modify-write is precisely the race that oversells, and no
 * amount of care in the service layer removes it. Reserving goes through a single conditional
 * {@code UPDATE ... WHERE on_hand - reserved >= :quantity} in
 * {@link com.flashcart.inventory.repository.StockItemRepository}, which is atomic by construction.
 *
 * <p>This entity exists for reads and for the low-contention admin paths, where {@link Version}
 * optimistic locking is the right tool.
 */
@Entity
@Table(name = "stock_items")
public class StockItem {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 64)
	private String sku;

	/** Physically present, including units held by an unpaid reservation. */
	@Column(name = "on_hand", nullable = false)
	private int onHand;

	/** Held by a live reservation. */
	@Column(nullable = false)
	private int reserved;

	@Version
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StockItem() {
	}

	public StockItem(UUID id, String sku, int onHand) {
		this.id = id;
		this.sku = sku;
		this.onHand = onHand;
		this.reserved = 0;
	}

	/** What a new buyer could still take right now. */
	public int available() {
		return onHand - reserved;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public int getOnHand() {
		return onHand;
	}

	public void setOnHand(int onHand) {
		this.onHand = onHand;
	}

	public int getReserved() {
		return reserved;
	}

	public void setReserved(int reserved) {
		this.reserved = reserved;
	}

	public Long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
