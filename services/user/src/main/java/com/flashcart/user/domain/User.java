package com.flashcart.user.domain;

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

/**
 * A shopper.
 *
 * <p>The identifier is what every other service already calls {@code customerId}. That is not a
 * coincidence to be tidied up later: orders placed before this service existed carry opaque strings
 * in that column, and they stay valid. Nothing migrates, nothing is rewritten, and an order from
 * Phase 4 still reads correctly — it simply has a customer id that no longer resolves to an account.
 */
@Entity
@Table(name = "users")
public class User {

	public enum Status { ACTIVE, DISABLED }

	@Id
	private UUID id;

	/** Stored lowercased, which is what makes the unique constraint mean what a reader expects. */
	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACTIVE;

	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Address> addresses = new ArrayList<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected User() {
	}

	public static User register(String email, String passwordHash, String displayName) {
		User user = new User();
		user.id = UUID.randomUUID();
		user.email = email.trim().toLowerCase();
		user.passwordHash = passwordHash;
		user.displayName = displayName.trim();
		return user;
	}

	/**
	 * Adds an address, and keeps the "exactly one default" rule true on this side too.
	 *
	 * <p>The database enforces it with a partial unique index, which is the thing that cannot be
	 * bypassed. This clears the previous default so that enforcement never has to fire: a constraint
	 * violation is a correct outcome but a poor error message.
	 */
	public Address addAddress(Address address) {
		if (!address.isDefault() && addresses.isEmpty()) {
			// The first address is the default whether or not the caller said so. Nobody means "add
			// my only address, but do not use it".
			address.makeDefault();
		}
		address.attachTo(this);
		addresses.add(address);
		touch();
		return address;
	}

	/**
	 * Demotes whichever address is currently the default.
	 *
	 * <p>Separate from {@link #addAddress} and called before it, because the two writes have to reach
	 * the database in that order. Hibernate does not guarantee that: left to its own flush ordering it
	 * inserts the new row before updating the old one, and the partial unique index -- correctly --
	 * rejects the moment when two rows claim to be the default. The constraint is not the problem;
	 * the order is, so the service flushes between them.
	 */
	public void clearDefaultAddress() {
		addresses.forEach(Address::clearDefault);
		touch();
	}

	public void rename(String displayName) {
		this.displayName = displayName.trim();
		touch();
	}

	public void disable() {
		this.status = Status.DISABLED;
		touch();
	}

	private void touch() {
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Status getStatus() {
		return status;
	}

	public List<Address> getAddresses() {
		return addresses;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
