package com.flashcart.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import com.flashcart.inventory.domain.CustomerSaleLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerSaleLimitRepository extends JpaRepository<CustomerSaleLimit, UUID> {

	Optional<CustomerSaleLimit> findByCustomerIdAndFlashSaleIdAndSku(String customerId, UUID flashSaleId, String sku);

	/**
	 * Charge {@code quantity} units against this customer's cap for the sale, atomically, or do
	 * nothing.
	 *
	 * <p>A read-then-check-then-write here would be defeated by the exact behaviour the cap exists to
	 * stop: a scalper firing fifty concurrent requests, every one of which reads zero and passes.
	 * {@code ON CONFLICT ... DO UPDATE ... WHERE} makes the check part of the write, so at most
	 * {@code limit} units get through however many requests arrive at once.
	 *
	 * <p>The {@code WHERE} guards only the conflict branch. The insert branch — the customer's first
	 * request for this SKU — is guarded by the caller comparing {@code quantity} against the limit,
	 * which is a pure comparison of two values already in hand and therefore raceless.
	 *
	 * @return 1 when charged, 0 when it would exceed the cap
	 */
	@Modifying
	@Query(value = """
			insert into customer_sale_limits (id, customer_id, flash_sale_id, sku, consumed_units,
			                                  created_at, updated_at)
			values (:id, :customerId, :flashSaleId, :sku, :quantity, now(), now())
			on conflict (customer_id, flash_sale_id, sku)
			do update set consumed_units = customer_sale_limits.consumed_units + :quantity,
			              updated_at     = now()
			        where customer_sale_limits.consumed_units + :quantity <= :limit
			""", nativeQuery = true)
	int tryConsume(@Param("id") UUID id, @Param("customerId") String customerId,
			@Param("flashSaleId") UUID flashSaleId, @Param("sku") String sku,
			@Param("quantity") int quantity, @Param("limit") int limit);

	/**
	 * Give a customer their allowance back.
	 *
	 * <p>Called on release and on expiry, but deliberately <em>not</em> on commit: units the customer
	 * actually bought must keep counting against the cap, or "one per customer" would mean "one at a
	 * time".
	 */
	@Modifying
	@Query(value = """
			update customer_sale_limits
			   set consumed_units = consumed_units - :quantity,
			       updated_at     = now()
			 where customer_id   = :customerId
			   and flash_sale_id = :flashSaleId
			   and sku           = :sku
			   and consumed_units >= :quantity
			""", nativeQuery = true)
	int release(@Param("customerId") String customerId, @Param("flashSaleId") UUID flashSaleId,
			@Param("sku") String sku, @Param("quantity") int quantity);
}
