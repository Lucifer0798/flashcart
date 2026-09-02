package com.flashcart.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** One attempt to take money for one order. */
@Entity
@Table(name = "payments")
public class Payment {

	@Id
	private UUID id;

	@Column(name = "order_id", nullable = false)
	private UUID orderId;

	@Column(name = "order_number", nullable = false, length = 32)
	private String orderNumber;

	@Column(name = "customer_id", nullable = false, length = 100)
	private String customerId;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PaymentStatus status;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
	private String idempotencyKey;

	@Column(name = "provider_reference", length = 100)
	private String providerReference;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "failure_reason", length = 300)
	private String failureReason;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "settled_at")
	private Instant settledAt;

	@Version
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Payment() {
	}

	public Payment(UUID id, UUID orderId, String orderNumber, String customerId, BigDecimal amount,
			String currency, String idempotencyKey, Instant requestedAt) {
		this.id = id;
		this.orderId = orderId;
		this.orderNumber = orderNumber;
		this.customerId = customerId;
		this.amount = amount;
		this.currency = currency;
		this.idempotencyKey = idempotencyKey;
		this.requestedAt = requestedAt;
		this.status = PaymentStatus.PENDING;
	}

	public void complete(String providerReference, Instant at) {
		this.status = PaymentStatus.COMPLETED;
		this.providerReference = providerReference;
		this.settledAt = at;
	}

	public void fail(String code, String reason, Instant at) {
		this.status = PaymentStatus.FAILED;
		this.failureCode = code;
		this.failureReason = reason;
		this.settledAt = at;
	}

	/** No {@code settledAt}: nothing has settled, which is the entire problem with this outcome. */
	public void timeOut(String reason) {
		this.status = PaymentStatus.TIMED_OUT;
		this.failureCode = "PAYMENT_TIMEOUT";
		this.failureReason = reason;
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

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getProviderReference() {
		return providerReference;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public Instant getSettledAt() {
		return settledAt;
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
