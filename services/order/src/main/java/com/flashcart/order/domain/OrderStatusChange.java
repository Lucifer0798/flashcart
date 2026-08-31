package com.flashcart.order.domain;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * One transition the state machine permitted, recorded as it happened.
 *
 * <p>{@code orders.status} says where an order is; this says how it got there. "Why is this order
 * cancelled" should not be a question answerable only from application logs that may have rotated
 * away — an order's path through the machine is exactly what support and reconciliation need.
 *
 * <p>{@link Persistable} with an always-true {@code isNew()} because the table is append-only: with
 * an assigned id and no version column, {@code save()} would otherwise {@code merge()} and issue a
 * pointless {@code SELECT} before every {@code INSERT}.
 */
@Entity
@Table(name = "order_status_history")
public class OrderStatusChange implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "order_id", nullable = false)
	private UUID orderId;

	/** Null for the first entry, where the order came into existence. */
	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 24)
	private OrderStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 24)
	private OrderStatus toStatus;

	@Column(length = 300)
	private String reason;

	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OrderStatusChange() {
	}

	public OrderStatusChange(UUID id, UUID orderId, OrderStatus fromStatus, OrderStatus toStatus,
			String reason, String correlationId) {
		this.id = id;
		this.orderId = orderId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.reason = reason;
		this.correlationId = correlationId;
	}

	@Override
	public UUID getId() {
		return id;
	}

	/** Always true: history is appended, never edited. */
	@Override
	public boolean isNew() {
		return true;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public OrderStatus getFromStatus() {
		return fromStatus;
	}

	public OrderStatus getToStatus() {
		return toStatus;
	}

	public String getReason() {
		return reason;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
