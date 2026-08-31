package com.flashcart.order.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.order.OrderStateMachine;
import com.flashcart.common.order.OrderStatus;
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

/**
 * What a customer committed to, and the state machine that governs it.
 *
 * <p>This is the aggregate the whole platform turns around: its status is the single fact inventory,
 * payment and shipping all react to. It deliberately holds no stock counts and no card details — it
 * orchestrates, it does not duplicate.
 *
 * <h2>Transitions go through the machine, always</h2>
 *
 * {@link #transitionTo} is the only way the status changes, and it asks
 * {@link OrderStateMachine#assertTransition} first. That is what makes the platform safe against the
 * thing distributed systems guarantee will happen: a payment callback delivered twice finds the
 * order already {@code PAID} and is rejected, because {@code PAID → PAID} is not an edge. A
 * reservation-expiry timer firing after the charge settled is rejected for the same reason. Neither
 * needs the caller to remember to check.
 *
 * <p>The rules live in {@code flashcart-common} rather than here because the state names travel on
 * the event bus from Phase 5, and a fork between what this service enforces and what a consumer
 * believes would be very hard to see.
 */
@Entity
@Table(name = "orders")
public class Order {

	@Id
	private UUID id;

	/** Human-facing reference. Customers quote this at support; the UUID is for machines. */
	@Column(name = "order_number", nullable = false, unique = true, length = 32)
	private String orderNumber;

	@Column(name = "customer_id", nullable = false, length = 100)
	private String customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private OrderStatus status;

	/** Set when placed against a flash sale, so inventory enforces the allocation and the cap. */
	@Column(name = "flash_sale_id")
	private UUID flashSaleId;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal subtotal;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal total;

	/**
	 * The key handed to inventory — the order id as a string. Reusing it makes inventory's reserve
	 * idempotent for free, which is what lets a timed-out reserve be retried rather than guessed at.
	 */
	@Column(name = "reservation_key", length = 100)
	private String reservationKey;

	/** Mirrored from inventory's reply, so the reconciler can find lapsed holds without being told. */
	@Column(name = "reservation_expires_at")
	private Instant reservationExpiresAt;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
	private String idempotencyKey;

	@Column(name = "cancellation_reason", length = 300)
	private String cancellationReason;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderLine> lines = new ArrayList<>();

	/**
	 * Several actors move an order concurrently — the customer cancelling, a payment callback, the
	 * expiry reconciler. Two landing together must not silently interleave.
	 */
	@Version
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Order() {
	}

	public Order(UUID id, String orderNumber, String customerId, UUID flashSaleId, String currency,
			String idempotencyKey) {
		this.id = id;
		this.orderNumber = orderNumber;
		this.customerId = customerId;
		this.flashSaleId = flashSaleId;
		this.currency = currency;
		this.idempotencyKey = idempotencyKey;
		this.status = OrderStatus.CREATED;
		this.reservationKey = id.toString();
		this.subtotal = BigDecimal.ZERO;
		this.total = BigDecimal.ZERO;
	}

	public void addLine(OrderLine line) {
		lines.add(line);
		line.setOrder(this);
		recalculateTotals();
	}

	private void recalculateTotals() {
		this.subtotal = lines.stream()
				.map(OrderLine::getLineTotal)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		// Subtotal and total are separate columns even though nothing yet distinguishes them.
		// Shipping and tax land between them later, and retrofitting a total that was never stored
		// means recomputing history with today's rules.
		this.total = this.subtotal;
	}

	/**
	 * Move to {@code next}, or refuse.
	 *
	 * @return the history entry to persist, so the audit trail cannot be forgotten by a caller
	 * @throws com.flashcart.common.order.IllegalOrderTransitionException when the move is not an edge
	 *         on the state machine — which is how duplicate and out-of-order events are rejected
	 */
	public OrderStatusChange transitionTo(OrderStatus next, String reason, String correlationId) {
		OrderStateMachine.assertTransition(this.status, next);
		OrderStatus previous = this.status;
		this.status = next;
		if (next == OrderStatus.CANCELLED && reason != null) {
			this.cancellationReason = reason;
		}
		return new OrderStatusChange(UUID.randomUUID(), this.id, previous, next, reason, correlationId);
	}

	/** The entry recording the order's creation, which has no previous state. */
	public OrderStatusChange creationRecord(String correlationId) {
		return new OrderStatusChange(UUID.randomUUID(), this.id, null, OrderStatus.CREATED,
				"order placed", correlationId);
	}

	/**
	 * True when this order is holding stock whose time is up.
	 *
	 * <p>{@link Clock} is a parameter rather than {@code Instant.now()} so the boundary is testable
	 * without sleeping through a real reservation TTL.
	 */
	public boolean hasExpiredReservation(Clock clock) {
		return status == OrderStatus.RESERVED
				&& reservationExpiresAt != null
				&& !clock.instant().isBefore(reservationExpiresAt);
	}

	/** True while this order is still holding stock that a compensation would have to give back. */
	public boolean holdsInventory() {
		return status == OrderStatus.RESERVED || status == OrderStatus.PAYMENT_PENDING;
	}

	public UUID getId() {
		return id;
	}

	public String getOrderNumber() {
		return orderNumber;
	}

	public String getCustomerId() {
		return customerId;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public UUID getFlashSaleId() {
		return flashSaleId;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getSubtotal() {
		return subtotal;
	}

	public BigDecimal getTotal() {
		return total;
	}

	public String getReservationKey() {
		return reservationKey;
	}

	public Instant getReservationExpiresAt() {
		return reservationExpiresAt;
	}

	public void setReservationExpiresAt(Instant reservationExpiresAt) {
		this.reservationExpiresAt = reservationExpiresAt;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getCancellationReason() {
		return cancellationReason;
	}

	public List<OrderLine> getLines() {
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
