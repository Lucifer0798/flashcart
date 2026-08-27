package com.flashcart.catalog.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.flashcart.catalog.domain.FlashSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashSaleItemRepository extends JpaRepository<FlashSaleItem, UUID> {

	boolean existsByFlashSaleIdAndProductId(UUID flashSaleId, UUID productId);

	List<FlashSaleItem> findByFlashSaleId(UUID flashSaleId);

	boolean existsByProductId(UUID productId);

	/**
	 * Every live offer covering any of {@code productIds}, in one query.
	 *
	 * <p>Batched by design: pricing a page of 50 products one product at a time is the classic N+1
	 * that turns a fast listing into a slow one exactly when a sale makes it busiest. The caller
	 * groups the result by product id.
	 */
	@Query("""
			select i from FlashSaleItem i
			join fetch i.flashSale s
			join fetch i.product p
			where p.id in :productIds
			  and s.status = com.flashcart.catalog.domain.FlashSaleStatus.SCHEDULED
			  and s.startsAt <= :now
			  and s.endsAt   >  :now
			""")
	List<FlashSaleItem> findLiveOffersForProducts(@Param("productIds") Collection<UUID> productIds,
			@Param("now") Instant now);

	@Query("""
			select i from FlashSaleItem i
			join fetch i.flashSale s
			join fetch i.product p
			where s.id = :flashSaleId
			""")
	List<FlashSaleItem> findByFlashSaleIdWithProduct(@Param("flashSaleId") UUID flashSaleId);
}
