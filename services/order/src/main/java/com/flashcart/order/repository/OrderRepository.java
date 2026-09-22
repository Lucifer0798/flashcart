package com.flashcart.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.order.domain.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

	@Override
	@EntityGraph(attributePaths = "lines")
	Optional<Order> findById(UUID id);

	@EntityGraph(attributePaths = "lines")
	Optional<Order> findByOrderNumber(String orderNumber);

	@EntityGraph(attributePaths = "lines")
	Optional<Order> findByIdempotencyKey(String idempotencyKey);

	@EntityGraph(attributePaths = "lines")
	List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId);

	boolean existsByOrderNumber(String orderNumber);

	long countByStatus(OrderStatus status);

	/**
	 * Orders still holding stock whose reservation has run out.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} so several reconciler instances can run at once without
	 * queueing behind each other on the same rows — and so a single slow order never blocks the
	 * rest of the batch.
	 *
	 * <p>Only {@code RESERVED} is a candidate. An order already in {@code PAYMENT_PENDING} has a
	 * charge in flight, and reclaiming its stock from underneath a payment that might yet succeed is
	 * exactly the mistake the {@code PAYMENT_TIMEOUT} path exists to avoid.
	 */
	@Query(value = """
			select o.id
			  from orders o
			 where o.status = 'RESERVED'
			   and o.reservation_expires_at is not null
			   and o.reservation_expires_at <= :now
			 order by o.reservation_expires_at
			 limit :maxRows
			   for update skip locked
			""", nativeQuery = true)
	List<UUID> claimExpiredReservations(@Param("now") Instant now, @Param("maxRows") int maxRows);

	/**
	 * Orders that asked shipping whether they could be cancelled and have not been answered.
	 *
	 * <p>Ordered by {@code updated_at} rather than a mirrored deadline, because unlike an expiring
	 * reservation there is no deadline to mirror — the order service holds no fact that would let it
	 * decide this for itself. The timestamp is only "how long we have been waiting", and nothing
	 * writes to the row while it waits, so it stays put.
	 *
	 * <p>Served by {@code ix_orders_cancellation_requested}, which is partial and normally empty.
	 */
	@Query(value = """
			select o.id
			  from orders o
			 where o.status = 'CANCELLATION_REQUESTED'
			   and o.updated_at <= :cutoff
			 order by o.updated_at
			 limit :maxRows
			   for update skip locked
			""", nativeQuery = true)
	List<UUID> claimUnansweredCancellations(@Param("cutoff") Instant cutoff, @Param("maxRows") int maxRows);

	/**
	 * Records that the cancellation backstop asked shipping again.
	 *
	 * <p>Nothing about the order has changed — it is still waiting — so there is no field for
	 * Hibernate to notice and no {@code @UpdateTimestamp} fires. Without this the row would keep its
	 * original timestamp, stay permanently past the cutoff, and be re-asked on every tick rather than
	 * once per timeout: a shipping outage would turn one stuck order into a command every fifteen
	 * seconds, forever.
	 *
	 * <p>{@code version} is deliberately left alone. This is not a change to the order, it is a note
	 * about when somebody last chased it, and bumping the version would make a concurrent write fail
	 * over a timestamp.
	 */
	@Modifying
	@Query(value = "update orders set updated_at = :now where id = :id", nativeQuery = true)
	void markCancellationReasked(@Param("id") UUID id, @Param("now") Instant now);
}
