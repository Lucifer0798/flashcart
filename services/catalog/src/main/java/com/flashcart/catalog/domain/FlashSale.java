package com.flashcart.catalog.domain;

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

/** A time-boxed sale: a window, and a set of products offered at a cut price inside it. */
@Entity
@Table(name = "flash_sales")
public class FlashSale {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 160)
	private String slug;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private FlashSaleStatus status;

	@OneToMany(mappedBy = "flashSale", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<FlashSaleItem> items = new ArrayList<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected FlashSale() {
	}

	public FlashSale(UUID id, String slug, String name, Instant startsAt, Instant endsAt, FlashSaleStatus status) {
		this.id = id;
		this.slug = slug;
		this.name = name;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = status;
	}

	/**
	 * Where this sale sits relative to {@code clock} — derived, never stored, so it can never be
	 * stale. {@link Clock} is a parameter rather than {@code Instant.now()} so the boundary
	 * behaviour is testable without sleeping.
	 */
	public FlashSalePhase phase(Clock clock) {
		if (status == FlashSaleStatus.CANCELLED) {
			return FlashSalePhase.CANCELLED;
		}
		if (status == FlashSaleStatus.DRAFT) {
			return FlashSalePhase.DRAFT;
		}
		Instant now = clock.instant();
		if (now.isBefore(startsAt)) {
			return FlashSalePhase.UPCOMING;
		}
		// The window is inclusive of startsAt and exclusive of endsAt, so back-to-back sales on the
		// same product cannot both be live for one instant.
		if (now.isBefore(endsAt)) {
			return FlashSalePhase.ACTIVE;
		}
		return FlashSalePhase.ENDED;
	}

	public boolean isLive(Clock clock) {
		return phase(clock) == FlashSalePhase.ACTIVE;
	}

	public void addItem(FlashSaleItem item) {
		items.add(item);
		item.setFlashSale(this);
	}

	public UUID getId() {
		return id;
	}

	public String getSlug() {
		return slug;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public void setStartsAt(Instant startsAt) {
		this.startsAt = startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public void setEndsAt(Instant endsAt) {
		this.endsAt = endsAt;
	}

	public FlashSaleStatus getStatus() {
		return status;
	}

	public void setStatus(FlashSaleStatus status) {
		this.status = status;
	}

	public List<FlashSaleItem> getItems() {
		return items;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
