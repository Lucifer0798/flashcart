package com.flashcart.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.common.order.OrderStatus;
import com.flashcart.order.domain.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
