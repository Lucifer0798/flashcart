package com.flashcart.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** One product's terms inside a flash sale: its price, its allocation, and its per-customer cap. */
@Entity
@Table(name = "flash_sale_items")
public class FlashSaleItem {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "flash_sale_id", nullable = false)
	private FlashSale flashSale;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal salePrice;

	/**
	 * How many units the sale may sell. Stated here, enforced by the inventory service in Phase 3 —
	 * catalog has no way to hold a count correctly under thousands of concurrent buyers, and
	 * pretending otherwise is how platforms oversell.
	 */
	@Column(name = "allocated_units", nullable = false)
	private int allocatedUnits;

	/** Anti-scalper cap, applied per customer per sale. */
	@Column(name = "per_customer_limit", nullable = false)
	private int perCustomerLimit;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected FlashSaleItem() {
	}

	public FlashSaleItem(UUID id, Product product, BigDecimal salePrice, int allocatedUnits, int perCustomerLimit) {
		this.id = id;
		this.product = product;
		this.salePrice = salePrice;
		this.allocatedUnits = allocatedUnits;
		this.perCustomerLimit = perCustomerLimit;
	}

	public UUID getId() {
		return id;
	}

	public FlashSale getFlashSale() {
		return flashSale;
	}

	void setFlashSale(FlashSale flashSale) {
		this.flashSale = flashSale;
	}

	public Product getProduct() {
		return product;
	}

	public BigDecimal getSalePrice() {
		return salePrice;
	}

	public void setSalePrice(BigDecimal salePrice) {
		this.salePrice = salePrice;
	}

	public int getAllocatedUnits() {
		return allocatedUnits;
	}

	public void setAllocatedUnits(int allocatedUnits) {
		this.allocatedUnits = allocatedUnits;
	}

	public int getPerCustomerLimit() {
		return perCustomerLimit;
	}

	public void setPerCustomerLimit(int perCustomerLimit) {
		this.perCustomerLimit = perCustomerLimit;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
