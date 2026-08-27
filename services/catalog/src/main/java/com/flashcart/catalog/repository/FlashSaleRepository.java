package com.flashcart.catalog.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.catalog.domain.FlashSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashSaleRepository extends JpaRepository<FlashSale, UUID> {

	Optional<FlashSale> findBySlug(String slug);

	boolean existsBySlug(String slug);

	/**
	 * Sales that are live at {@code now}.
	 *
	 * <p>The window is half-open — {@code startsAt <= now < endsAt} — so two back-to-back sales on
	 * the same product can never both be live for the instant they touch.
	 */
	@Query("""
			select s from FlashSale s
			where s.status = com.flashcart.catalog.domain.FlashSaleStatus.SCHEDULED
			  and s.startsAt <= :now
			  and s.endsAt   >  :now
			order by s.endsAt asc
			""")
	List<FlashSale> findLive(@Param("now") Instant now);

	@Query("""
			select s from FlashSale s
			where s.status = com.flashcart.catalog.domain.FlashSaleStatus.SCHEDULED
			  and s.startsAt > :now
			order by s.startsAt asc
			""")
	List<FlashSale> findUpcoming(@Param("now") Instant now);
}
