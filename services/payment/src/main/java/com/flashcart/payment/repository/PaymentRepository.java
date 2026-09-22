package com.flashcart.payment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

	Optional<Payment> findByIdempotencyKey(String idempotencyKey);

	List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

	Optional<Payment> findByOrderNumber(String orderNumber);

	List<Payment> findByCustomerIdOrderByCreatedAtDesc(String customerId);

	long countByStatus(PaymentStatus status);

	/**
	 * Attempts that have been pending longer than they should be.
	 *
	 * <p>{@code SKIP LOCKED} so several instances can reconcile at once without queueing on the same
	 * rows.
	 */
	@Query(value = """
			select p.id
			  from payments p
			 where p.status = 'PENDING'
			   and p.requested_at <= :cutoff
			 order by p.requested_at
			 limit :maxRows
			   for update skip locked
			""", nativeQuery = true)
	List<UUID> claimStalePending(@Param("cutoff") Instant cutoff, @Param("maxRows") int maxRows);

	/**
	 * Refunds the provider refused, that are still worth trying again.
	 *
	 * <p>{@code updated_at} is the last attempt, because a refusal writes the row and nothing else
	 * touches it in between. A dedicated column would say the same thing and would have to be kept
	 * true by hand.
	 *
	 * <p>The attempt cap is in the query rather than applied after loading, so a payment that has been
	 * given up on stops being claimed at all. It stays {@code REFUND_FAILED} and stays findable by
	 * {@code ix_payments_refund_failed}: giving up on retrying is not the same as deciding the money
	 * is not owed.
	 */
	@Query(value = """
			select p.id
			  from payments p
			 where p.status = 'REFUND_FAILED'
			   and p.refund_attempts < :maxAttempts
			   and p.updated_at <= :cutoff
			 order by p.updated_at
			 limit :maxRows
			   for update skip locked
			""", nativeQuery = true)
	List<UUID> claimRetryableRefunds(@Param("cutoff") Instant cutoff,
			@Param("maxAttempts") int maxAttempts, @Param("maxRows") int maxRows);
}
