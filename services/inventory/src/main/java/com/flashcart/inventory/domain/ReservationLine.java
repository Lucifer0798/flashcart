package com.flashcart.inventory.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One SKU and quantity within a reservation. */
@Entity
@Table(name = "reservation_lines")
public class ReservationLine {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id", nullable = false)
	private Reservation reservation;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(nullable = false)
	private int quantity;

	protected ReservationLine() {
	}

	public ReservationLine(UUID id, String sku, int quantity) {
		this.id = id;
		this.sku = sku;
		this.quantity = quantity;
	}

	public UUID getId() {
		return id;
	}

	public Reservation getReservation() {
		return reservation;
	}

	void setReservation(Reservation reservation) {
		this.reservation = reservation;
	}

	public String getSku() {
		return sku;
	}

	public int getQuantity() {
		return quantity;
	}
}
