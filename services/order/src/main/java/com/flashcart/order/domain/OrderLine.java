package com.flashcart.order.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One SKU on an order, priced at the moment the order was placed.
 *
 * <p>{@code productName} and {@code unitPrice} are copied from catalog rather than referenced. A
 * product can be renamed, repriced or archived the day after a sale, and an order from last month
 * must still show what the customer actually bought and was actually charged.
 */
@Entity
@Table(name = "order_lines")
public class OrderLine {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(name = "product_name", nullable = false, length = 200)
	private String productName;

	@Column(nullable = false)
	private int quantity;

	@Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "line_total", nullable = false, precision = 12, scale = 2)
	private BigDecimal lineTotal;

	protected OrderLine() {
	}

	public OrderLine(UUID id, String sku, String productName, int quantity, BigDecimal unitPrice) {
		this.id = id;
		this.sku = sku;
		this.productName = productName;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
		this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
	}

	public UUID getId() {
		return id;
	}

	public Order getOrder() {
		return order;
	}

	void setOrder(Order order) {
		this.order = order;
	}

	public String getSku() {
		return sku;
	}

	public String getProductName() {
		return productName;
	}

	public int getQuantity() {
		return quantity;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public BigDecimal getLineTotal() {
		return lineTotal;
	}
}
