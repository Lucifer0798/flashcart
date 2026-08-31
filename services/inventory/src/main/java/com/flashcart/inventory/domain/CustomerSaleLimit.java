package com.flashcart.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * How much of a flash sale one customer has taken — the anti-scalper cap.
 *
 * <p>{@code consumedUnits} counts units currently held plus units already bought. Committed units
 * keep counting; released and expired holds decrement, so a customer whose hold timed out is free to
 * try again while one who actually bought is not.
 *
 * <p>Written through
 * {@link com.flashcart.inventory.repository.CustomerSaleLimitRepository#tryConsume}, never by
 * loading and saving this entity — see that method for why.
 */
@Entity
@Table(name = "customer_sale_limits")
public class CustomerSaleLimit {

	@Id
	private UUID id;

	@Column(name = "customer_id", nullable = false, length = 100)
	private String customerId;

	@Column(name = "flash_sale_id", nullable = false)
	private UUID flashSaleId;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(name = "consumed_units", nullable = false)
	private int consumedUnits;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CustomerSaleLimit() {
	}

	public UUID getId() {
		return id;
	}

	public String getCustomerId() {
		return customerId;
	}

	public UUID getFlashSaleId() {
		return flashSaleId;
	}

	public String getSku() {
		return sku;
	}

	public int getConsumedUnits() {
		return consumedUnits;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
