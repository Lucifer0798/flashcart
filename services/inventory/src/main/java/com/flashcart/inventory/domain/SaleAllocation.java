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
 * The slice of a SKU's stock that one flash sale is permitted to sell.
 *
 * <p>Checked <em>in addition to</em> {@link StockItem}, never instead of it: a warehouse holding
 * 5,000 units can still run a sale that is only allowed to move 500. Both conditions must pass for a
 * reservation to succeed, which is why a reserve is two conditional updates rather than one.
 */
@Entity
@Table(name = "sale_allocations")
public class SaleAllocation {

	@Id
	private UUID id;

	@Column(name = "flash_sale_id", nullable = false)
	private UUID flashSaleId;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(name = "allocated_units", nullable = false)
	private int allocatedUnits;

	@Column(name = "reserved_units", nullable = false)
	private int reservedUnits;

	/** Commit moves units here from {@code reservedUnits}; the sum is what the sale has consumed. */
	@Column(name = "committed_units", nullable = false)
	private int committedUnits;

	@Column(name = "per_customer_limit", nullable = false)
	private int perCustomerLimit;

	@Version
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SaleAllocation() {
	}

	public SaleAllocation(UUID id, UUID flashSaleId, String sku, int allocatedUnits, int perCustomerLimit) {
		this.id = id;
		this.flashSaleId = flashSaleId;
		this.sku = sku;
		this.allocatedUnits = allocatedUnits;
		this.perCustomerLimit = perCustomerLimit;
	}

	/** Units of this allocation still up for grabs. */
	public int remainingUnits() {
		return allocatedUnits - reservedUnits - committedUnits;
	}

	public UUID getId() {
		return id;
	}

	public UUID getFlashSaleId() {
		return flashSaleId;
	}

	public String getSku() {
		return sku;
	}

	public int getAllocatedUnits() {
		return allocatedUnits;
	}

	public void setAllocatedUnits(int allocatedUnits) {
		this.allocatedUnits = allocatedUnits;
	}

	public int getReservedUnits() {
		return reservedUnits;
	}

	public int getCommittedUnits() {
		return committedUnits;
	}

	public int getPerCustomerLimit() {
		return perCustomerLimit;
	}

	public void setPerCustomerLimit(int perCustomerLimit) {
		this.perCustomerLimit = perCustomerLimit;
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
