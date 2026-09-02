package com.flashcart.shipping.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One SKU in a consignment. No prices: what a parcel costs is nobody's business in a warehouse. */
@Entity
@Table(name = "shipment_lines")
public class ShipmentLine {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shipment_id", nullable = false)
	private Shipment shipment;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(nullable = false)
	private int quantity;

	protected ShipmentLine() {
	}

	public ShipmentLine(UUID id, String sku, int quantity) {
		this.id = id;
		this.sku = sku;
		this.quantity = quantity;
	}

	public UUID getId() {
		return id;
	}

	public Shipment getShipment() {
		return shipment;
	}

	void setShipment(Shipment shipment) {
		this.shipment = shipment;
	}

	public String getSku() {
		return sku;
	}

	public int getQuantity() {
		return quantity;
	}
}
