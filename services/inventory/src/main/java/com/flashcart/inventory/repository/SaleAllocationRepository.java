package com.flashcart.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.inventory.domain.SaleAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleAllocationRepository extends JpaRepository<SaleAllocation, UUID> {

	Optional<SaleAllocation> findByFlashSaleIdAndSku(UUID flashSaleId, String sku);

	List<SaleAllocation> findByFlashSaleId(UUID flashSaleId);

	boolean existsByFlashSaleIdAndSku(UUID flashSaleId, String sku);

	/**
	 * Claim {@code quantity} units of this sale's allocation, atomically, or do nothing.
	 *
	 * <p>Same shape as {@code StockItemRepository.tryReserve} and for the same reason. This is the
	 * second of the two conditions a reservation must satisfy: the warehouse must have the units
	 * <em>and</em> the sale must still be allowed to sell them.
	 *
	 * @return 1 when claimed, 0 when the sale has already committed its whole allocation
	 */
	@Modifying
	@Query(value = """
			update sale_allocations
			   set reserved_units = reserved_units + :quantity,
			       version        = version + 1,
			       updated_at     = now()
			 where flash_sale_id = :flashSaleId
			   and sku           = :sku
			   and allocated_units - reserved_units - committed_units >= :quantity
			""", nativeQuery = true)
	int tryReserve(@Param("flashSaleId") UUID flashSaleId, @Param("sku") String sku,
			@Param("quantity") int quantity);

	@Modifying
	@Query(value = """
			update sale_allocations
			   set reserved_units = reserved_units - :quantity,
			       version        = version + 1,
			       updated_at     = now()
			 where flash_sale_id = :flashSaleId
			   and sku           = :sku
			   and reserved_units >= :quantity
			""", nativeQuery = true)
	int releaseReserved(@Param("flashSaleId") UUID flashSaleId, @Param("sku") String sku,
			@Param("quantity") int quantity);

	/** Moves units from held to sold, so the allocation's consumed total is unchanged by a commit. */
	@Modifying
	@Query(value = """
			update sale_allocations
			   set reserved_units  = reserved_units - :quantity,
			       committed_units = committed_units + :quantity,
			       version         = version + 1,
			       updated_at      = now()
			 where flash_sale_id = :flashSaleId
			   and sku           = :sku
			   and reserved_units >= :quantity
			""", nativeQuery = true)
	int commitReserved(@Param("flashSaleId") UUID flashSaleId, @Param("sku") String sku,
			@Param("quantity") int quantity);
}
