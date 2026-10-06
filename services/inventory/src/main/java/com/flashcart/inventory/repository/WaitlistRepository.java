package com.flashcart.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.inventory.domain.WaitlistEntry;
import com.flashcart.inventory.domain.WaitlistStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, UUID> {

	Optional<WaitlistEntry> findBySkuAndCustomerIdAndStatus(String sku, String customerId, WaitlistStatus status);

	List<WaitlistEntry> findByCustomerIdOrderByCreatedAtDesc(String customerId);

	/**
	 * Tell the oldest {@code quantity} waiters, and nobody else.
	 *
	 * <p>One statement that both chooses and marks, so the choice cannot be separated from the claim.
	 *
	 * <p>Two arrivals of the same SKU never race here: each has already updated, and so locked, the
	 * SKU's stock row. {@code for update skip locked} is for a different race -- a shopper leaving while
	 * units arrive. Their row is locked by the leave; without skipping it, this statement would wait,
	 * then re-check only {@code id in (...)} and mark the now-cancelled entry notified. Skipping it tells
	 * the next shopper instead. See ADR 0044.
	 *
	 * @return the entries this call notified, oldest first
	 */
	@Query(value = """
			update waitlist_entries
			   set status      = 'NOTIFIED',
			       notified_at = :at
			 where id in (select id
			                from waitlist_entries
			               where sku = :sku
			                 and status = 'WAITING'
			               order by created_at, id
			               limit :quantity
			                 for update skip locked)
			returning *
			""", nativeQuery = true)
	List<WaitlistEntry> claimOldest(@Param("sku") String sku, @Param("quantity") int quantity,
			@Param("at") Instant at);

	/** How many are ahead of this entry in the same queue: its position, counting from zero. */
	@Query(value = """
			select count(*)
			  from waitlist_entries
			 where sku = :sku
			   and status = 'WAITING'
			   and (created_at, id) < (:createdAt, :id)
			""", nativeQuery = true)
	long countAhead(@Param("sku") String sku, @Param("createdAt") Instant createdAt, @Param("id") UUID id);

	@Query(value = "select count(*) from waitlist_entries where sku = :sku and status = 'WAITING'",
			nativeQuery = true)
	long countWaiting(@Param("sku") String sku);
}
