package com.flashcart.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One shopper's place in the queue for one sold-out SKU.
 *
 * <p>Moved to {@link WaitlistStatus#NOTIFIED} only through
 * {@link com.flashcart.inventory.repository.WaitlistRepository#claimOldest}, never by loading and
 * saving this entity: the claim is what guarantees nobody is told twice when two releases of the same
 * SKU land at once.
 */
@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

	@Id
	private UUID id;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(name = "customer_id", nullable = false, length = 100)
	private String customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private WaitlistStatus status;

	/** Set by the application rather than the database default, so the queue order is the one the
	 *  service decided and a test can reason about it. */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "notified_at")
	private Instant notifiedAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected WaitlistEntry() {
	}

	public WaitlistEntry(UUID id, String sku, String customerId, Instant createdAt) {
		this.id = id;
		this.sku = sku;
		this.customerId = customerId;
		this.createdAt = createdAt;
		this.status = WaitlistStatus.WAITING;
	}

	public void cancel(Instant at) {
		this.status = WaitlistStatus.CANCELLED;
		this.cancelledAt = at;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getCustomerId() {
		return customerId;
	}

	public WaitlistStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getNotifiedAt() {
		return notifiedAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}
}
