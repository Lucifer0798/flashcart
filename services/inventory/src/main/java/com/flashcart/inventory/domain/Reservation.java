package com.flashcart.inventory.domain;

import java.time.Clock;
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

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A time-boxed hold on stock.
 *
 * <p>Reservations are what let a customer spend ninety seconds typing a card number without anything
 * holding a database lock across that think time. The hold is durable, bounded, and released three
 * ways: committed when payment lands, released when the order is abandoned, expired when the clock
 * beats both.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

	@Id
	private UUID id;

	/**
	 * The caller's idempotency key — normally the order id. Unique, because at-least-once retries
	 * are a certainty and a retried reserve must return the original hold rather than take a second.
	 */
	@Column(name = "reservation_key", nullable = false, unique = true, length = 100)
	private String reservationKey;

	@Column(name = "customer_id", nullable = false, length = 100)
	private String customerId;

	/** Set when this hold is against a flash sale; null for ordinary stock. */
	@Column(name = "flash_sale_id")
	private UUID flashSaleId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ReservationStatus status;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "committed_at")
	private Instant committedAt;

	@Column(name = "released_at")
	private Instant releasedAt;

	@OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ReservationLine> lines = new ArrayList<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Reservation() {
	}

	public Reservation(UUID id, String reservationKey, String customerId, UUID flashSaleId, Instant expiresAt) {
		this.id = id;
		this.reservationKey = reservationKey;
		this.customerId = customerId;
		this.flashSaleId = flashSaleId;
		this.expiresAt = expiresAt;
		this.status = ReservationStatus.HELD;
	}

	public void addLine(ReservationLine line) {
		lines.add(line);
		line.setReservation(this);
	}

	/**
	 * True when this hold is still running but its time is up.
	 *
	 * <p>{@link Clock} is a parameter rather than {@code Instant.now()} so expiry behaviour is
	 * testable at the boundary without sleeping through a real TTL.
	 */
	public boolean isExpired(Clock clock) {
		return status == ReservationStatus.HELD && !clock.instant().isBefore(expiresAt);
	}

	public UUID getId() {
		return id;
	}

	public String getReservationKey() {
		return reservationKey;
	}

	public String getCustomerId() {
		return customerId;
	}

	public UUID getFlashSaleId() {
		return flashSaleId;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public void setStatus(ReservationStatus status) {
		this.status = status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCommittedAt() {
		return committedAt;
	}

	public void setCommittedAt(Instant committedAt) {
		this.committedAt = committedAt;
	}

	public Instant getReleasedAt() {
		return releasedAt;
	}

	public void setReleasedAt(Instant releasedAt) {
		this.releasedAt = releasedAt;
	}

	public List<ReservationLine> getLines() {
		return lines;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
