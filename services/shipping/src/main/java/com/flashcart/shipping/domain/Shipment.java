package com.flashcart.shipping.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A consignment for one order. */
@Entity
@Table(name = "shipments")
public class Shipment {

	@Id
	private UUID id;

	/** Unique. One shipment per order, which is also what makes a redelivered command harmless. */
	@Column(name = "order_id", nullable = false, unique = true)
	private UUID orderId;

	@Column(name = "order_number", nullable = false, length = 32)
	private String orderNumber;

	@Column(name = "customer_id", nullable = false, length = 100)
	private String customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ShipmentStatus status;

	@Column(nullable = false, length = 64)
	private String carrier;

	@Column(name = "tracking_number", nullable = false, unique = true, length = 64)
	private String trackingNumber;

	@Column(name = "dispatched_at")
	private Instant dispatchedAt;

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	@OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ShipmentLine> lines = new ArrayList<>();

	@Version
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Shipment() {
	}

	public Shipment(UUID id, UUID orderId, String orderNumber, String customerId, String carrier,
			String trackingNumber) {
		this.id = id;
		this.orderId = orderId;
		this.orderNumber = orderNumber;
		this.customerId = customerId;
		this.carrier = carrier;
		this.trackingNumber = trackingNumber;
		this.status = ShipmentStatus.CREATED;
	}

	public void addLine(ShipmentLine line) {
		lines.add(line);
		line.setShipment(this);
	}

	public void dispatch(Instant at) {
		this.status = ShipmentStatus.DISPATCHED;
		this.dispatchedAt = at;
	}

	public void deliver(Instant at) {
		this.status = ShipmentStatus.DELIVERED;
		this.deliveredAt = at;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public String getOrderNumber() {
		return orderNumber;
	}

	public String getCustomerId() {
		return customerId;
	}

	public ShipmentStatus getStatus() {
		return status;
	}

	public String getCarrier() {
		return carrier;
	}

	public String getTrackingNumber() {
		return trackingNumber;
	}

	public Instant getDispatchedAt() {
		return dispatchedAt;
	}

	public Instant getDeliveredAt() {
		return deliveredAt;
	}

	public List<ShipmentLine> getLines() {
		return lines;
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
