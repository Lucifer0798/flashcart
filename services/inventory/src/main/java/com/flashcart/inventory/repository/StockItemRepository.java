package com.flashcart.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.inventory.domain.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

	Optional<StockItem> findBySku(String sku);

	List<StockItem> findBySkuIn(Collection<String> skus);

	boolean existsBySku(String sku);

	/**
	 * Take {@code quantity} units out of circulation, atomically, or do nothing.
	 *
	 * <p><strong>This one statement is what makes overselling structurally impossible.</strong> The
	 * obvious implementation — load the row, check {@code available >= quantity}, increment, save —
	 * has a window between the check and the write in which any number of other transactions can
	 * pass the same check. At a hundred concurrent buyers and five units left, that window sells
	 * fifty. No amount of care in the service layer closes it, because the race is in the shape of
	 * the operation, not in the code around it.
	 *
	 * <p>Here the predicate and the mutation are the same statement. PostgreSQL takes a row lock for
	 * its duration, re-evaluates {@code on_hand - reserved >= :quantity} against the committed row,
	 * and either updates or matches nothing. A return of {@code 0} means "someone else got there
	 * first" — an ordinary, expected outcome during a sale, not an error.
	 *
	 * <p>The row lock is held for microseconds and never across a network call or a user's think
	 * time; that is what {@link com.flashcart.inventory.domain.Reservation} is for.
	 *
	 * @return 1 when the units were held, 0 when there were not enough
	 */
	@Modifying
	@Query(value = """
			update stock_items
			   set reserved   = reserved + :quantity,
			       version    = version + 1,
			       updated_at = now()
			 where sku = :sku
			   and on_hand - reserved >= :quantity
			""", nativeQuery = true)
	int tryReserve(@Param("sku") String sku, @Param("quantity") int quantity);

	/**
	 * Give held units back. The {@code reserved >= :quantity} guard makes a double release a no-op
	 * rather than driving the counter negative — which matters, because release arrives from expiry,
	 * from explicit cancellation and from retried callers.
	 *
	 * @return 1 when the units were returned, 0 when they had already gone back
	 */
	@Modifying
	@Query(value = """
			update stock_items
			   set reserved   = reserved - :quantity,
			       version    = version + 1,
			       updated_at = now()
			 where sku = :sku
			   and reserved >= :quantity
			""", nativeQuery = true)
	int releaseReserved(@Param("sku") String sku, @Param("quantity") int quantity);

	/**
	 * Turn a hold into a sale: the units physically leave, so both counters drop together.
	 *
	 * @return 1 when committed, 0 when the hold was no longer there
	 */
	@Modifying
	@Query(value = """
			update stock_items
			   set on_hand    = on_hand - :quantity,
			       reserved   = reserved - :quantity,
			       version    = version + 1,
			       updated_at = now()
			 where sku = :sku
			   and reserved >= :quantity
			   and on_hand  >= :quantity
			""", nativeQuery = true)
	int commitReserved(@Param("sku") String sku, @Param("quantity") int quantity);

	/**
	 * The pessimistic alternative to {@link #tryReserve}, kept for the comparison Phase 10 will draw.
	 *
	 * <p>{@code SELECT ... FOR UPDATE} serialises every buyer of this SKU behind one row lock held
	 * for the rest of the transaction. It is just as correct, and markedly worse under a flash sale:
	 * the conditional update holds its lock for one statement, this holds it for everything the
	 * transaction does afterwards. It is the right tool only when a decision genuinely needs to read
	 * several values and then write, which reserving does not.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from StockItem s where s.sku = :sku")
	Optional<StockItem> findBySkuForUpdate(@Param("sku") String sku);
}
