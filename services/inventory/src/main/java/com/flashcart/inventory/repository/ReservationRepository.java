package com.flashcart.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.inventory.domain.Reservation;
import com.flashcart.inventory.domain.ReservationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	@EntityGraph(attributePaths = "lines")
	Optional<Reservation> findByReservationKey(String reservationKey);

	@Override
	@EntityGraph(attributePaths = "lines")
	Optional<Reservation> findById(UUID id);

	List<Reservation> findByCustomerIdOrderByCreatedAtDesc(String customerId);

	/**
	 * Claim expired holds that touch this SKU, for the lazy reclaim on the reserve path.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} is doing real work here. Under a flash sale, many requests
	 * for the same hot SKU arrive at once and every one of them tries to reclaim first. Without
	 * {@code SKIP LOCKED} they queue behind each other on the same rows and the reclaim becomes the
	 * bottleneck it was meant to avoid; with it, the first request takes the rows and the rest sail
	 * past to their actual job — the background sweeper included.
	 *
	 * <p>Bounded by {@code maxRows} so one unlucky request never inherits an unbounded backlog.
	 */
	@Query(value = """
			select r.id
			  from reservations r
			 where r.status = 'HELD'
			   and r.expires_at <= now()
			   and exists (select 1
			                 from reservation_lines l
			                where l.reservation_id = r.id
			                  and l.sku = :sku)
			 order by r.expires_at
			 limit :maxRows
			   for update skip locked
			""", nativeQuery = true)
	List<UUID> claimExpiredForSku(@Param("sku") String sku, @Param("maxRows") int maxRows);

	/** The same claim without a SKU filter, for the background sweeper. */
	@Query(value = """
			select r.id
			  from reservations r
			 where r.status = 'HELD'
			   and r.expires_at <= now()
			 order by r.expires_at
			 limit :maxRows
			   for update skip locked
			""", nativeQuery = true)
	List<UUID> claimExpired(@Param("maxRows") int maxRows);

	long countByStatus(ReservationStatus status);
}
