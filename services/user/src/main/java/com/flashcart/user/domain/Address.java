package com.flashcart.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Where to send things.
 *
 * <p>Ordinary mutable data with no history, deliberately. Shipping copies what it needs onto the
 * consignment when it is created, so editing an address here cannot rewrite where a parcel was
 * already sent — the same reasoning as prices, which orders capture rather than look up
 * (<a href="../../../../../../../../docs/adr/0011-order-owns-no-prices.md">ADR 0011</a>).
 */
@Entity
@Table(name = "addresses")
public class Address {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	private String label;

	@Column(nullable = false)
	private String line1;

	private String line2;

	@Column(nullable = false)
	private String city;

	@Column(nullable = false)
	private String postcode;

	/** ISO 3166-1 alpha-2, upper-cased, and checked by the database too. */
	@Column(nullable = false)
	private String country;

	@Column(name = "is_default", nullable = false)
	private boolean isDefault;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Address() {
	}

	public static Address of(String label, String line1, String line2, String city, String postcode,
			String country, boolean isDefault) {
		Address address = new Address();
		address.id = UUID.randomUUID();
		address.label = label;
		address.line1 = line1;
		address.line2 = line2;
		address.city = city;
		address.postcode = postcode;
		address.country = country.trim().toUpperCase();
		address.isDefault = isDefault;
		return address;
	}

	void attachTo(User user) {
		this.user = user;
	}

	void clearDefault() {
		this.isDefault = false;
		this.updatedAt = Instant.now();
	}

	void makeDefault() {
		this.isDefault = true;
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getLabel() {
		return label;
	}

	public String getLine1() {
		return line1;
	}

	public String getLine2() {
		return line2;
	}

	public String getCity() {
		return city;
	}

	public String getPostcode() {
		return postcode;
	}

	public String getCountry() {
		return country;
	}

	public boolean isDefault() {
		return isDefault;
	}
}
