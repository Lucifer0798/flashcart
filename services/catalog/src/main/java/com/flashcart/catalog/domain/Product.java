package com.flashcart.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A sellable item.
 *
 * <p>Holds no stock count on purpose: how many units are left is the inventory service's data.
 * Keeping a copy here would give the one number a flash sale cannot afford to get wrong two owners.
 */
@Entity
@Table(name = "products")
public class Product {

	@Id
	private UUID id;

	/** Stable merchant-facing identifier, upper-cased. What inventory and orders key on. */
	@Column(nullable = false, unique = true, length = 64)
	private String sku;

	@Column(nullable = false, unique = true, length = 160)
	private String slug;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(columnDefinition = "text")
	private String description;

	/**
	 * LAZY because the storefront listing renders hundreds of products per page and an EAGER
	 * association would fire one category select per row.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	/** List price. The price actually charged may be lower — see the flash-sale item. */
	@Column(name = "base_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal basePrice;

	@Column(nullable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ProductStatus status;

	@Column(name = "image_url", length = 500)
	private String imageUrl;

	/**
	 * Optimistic lock. Two admins editing the same product mid-sale is exactly when a lost update
	 * costs money, and a version check is far cheaper than holding a row lock across a think time.
	 */
	@Version
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Product() {
	}

	public Product(UUID id, String sku, String slug, String name, String description, Category category,
			BigDecimal basePrice, String currency, ProductStatus status, String imageUrl) {
		this.id = id;
		this.sku = sku;
		this.slug = slug;
		this.name = name;
		this.description = description;
		this.category = category;
		this.basePrice = basePrice;
		this.currency = currency;
		this.status = status;
		this.imageUrl = imageUrl;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getSlug() {
		return slug;
	}

	public void setSlug(String slug) {
		this.slug = slug;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Category getCategory() {
		return category;
	}

	public void setCategory(Category category) {
		this.category = category;
	}

	public BigDecimal getBasePrice() {
		return basePrice;
	}

	public void setBasePrice(BigDecimal basePrice) {
		this.basePrice = basePrice;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public ProductStatus getStatus() {
		return status;
	}

	public void setStatus(ProductStatus status) {
		this.status = status;
	}

	public String getImageUrl() {
		return imageUrl;
	}

	public void setImageUrl(String imageUrl) {
		this.imageUrl = imageUrl;
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
