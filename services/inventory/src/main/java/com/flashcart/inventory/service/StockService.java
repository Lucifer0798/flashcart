package com.flashcart.inventory.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.inventory.domain.MovementType;
import com.flashcart.inventory.domain.StockItem;
import com.flashcart.inventory.domain.StockMovement;
import com.flashcart.inventory.repository.StockItemRepository;
import com.flashcart.inventory.repository.StockMovementRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The warehouse-facing side: creating SKUs, receiving stock, correcting it.
 *
 * <p>These are low-contention, human-driven writes, so they use ordinary entity loads and optimistic
 * locking — the opposite of {@link ReservationService}'s conditional updates, and appropriately so.
 * Two warehouse staff adjusting the same SKU at once should be told about it; ten thousand shoppers
 * reserving it should not be serialised behind each other.
 */
@Service
@Transactional(readOnly = true)
public class StockService {

	private final StockItemRepository stockItems;
	private final StockMovementRepository movements;
	private final MovementRecorder recorder;
	private final AvailabilityGate gate;

	public StockService(StockItemRepository stockItems, StockMovementRepository movements,
			MovementRecorder recorder, AvailabilityGate gate) {
		this.stockItems = stockItems;
		this.movements = movements;
		this.recorder = recorder;
		this.gate = gate;
	}

	public StockItem get(String sku) {
		String normalized = normalise(sku);
		return stockItems.findBySku(normalized)
				.orElseThrow(() -> ResourceNotFoundException.of("Stock for SKU", sku));
	}

	/**
	 * Positions for several SKUs at once — what a storefront needs to badge a whole page.
	 *
	 * <p>Batched because the alternative is one call per product tile, which is exactly the pattern
	 * that collapses when a sale makes the listing busy.
	 */
	public List<StockItem> getAll(Collection<String> skus) {
		if (skus == null || skus.isEmpty()) {
			return List.of();
		}
		return stockItems.findBySkuIn(skus.stream().map(this::normalise).toList());
	}

	public Page<StockMovement> movements(String sku, Pageable pageable) {
		return movements.findBySkuOrderByCreatedAtDesc(normalise(sku), pageable);
	}

	public List<StockMovement> movementsForReservation(UUID reservationId) {
		return movements.findByReservationIdOrderByCreatedAtAsc(reservationId);
	}

	/** Create a SKU's stock position, optionally with an opening quantity. */
	@Transactional
	public StockItem create(String sku, int initialQuantity, String reason) {
		String normalized = normalise(sku);
		if (initialQuantity < 0) {
			throw new BadRequestException("Initial quantity cannot be negative");
		}
		if (stockItems.existsBySku(normalized)) {
			throw new ConflictException("SKU_ALREADY_TRACKED",
					"Stock for SKU %s is already tracked; use receive or adjust".formatted(normalized));
		}
		StockItem item = new StockItem(UUID.randomUUID(), normalized, initialQuantity);
		try {
			stockItems.saveAndFlush(item);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("SKU_ALREADY_TRACKED",
					"Stock for SKU %s is already tracked".formatted(normalized));
		}
		if (initialQuantity > 0) {
			recorder.record(normalized, MovementType.RECEIVED, initialQuantity, 0, null, null,
					reason == null ? "opening balance" : reason);
		}
		return item;
	}

	/** Stock arrived. Always additive; use {@link #adjust} for corrections. */
	@Transactional
	public StockItem receive(String sku, int quantity, String reason) {
		if (quantity <= 0) {
			throw new BadRequestException("Received quantity must be positive");
		}
		StockItem item = get(sku);
		item.setOnHand(item.getOnHand() + quantity);
		flushOrConflict(sku);
		recorder.record(item.getSku(), MovementType.RECEIVED, quantity, 0, null, null, reason);
		// Invalidated rather than incremented: a warehouse change is rare and the next reserve can
		// afford one database read to re-seed a counter that is now certainly correct.
		gate.invalidate(item.getSku());
		return item;
	}

	/**
	 * A signed correction — damage, shrinkage, a recount.
	 *
	 * <p>A negative adjustment can never take on-hand below what is currently held: those units are
	 * promised to someone. The database's {@code reserved <= on_hand} check would refuse it anyway;
	 * failing here turns a constraint violation into an explainable error.
	 */
	@Transactional
	public StockItem adjust(String sku, int delta, String reason) {
		if (delta == 0) {
			throw new BadRequestException("Adjustment delta must not be zero");
		}
		if (reason == null || reason.isBlank()) {
			// An unexplained adjustment makes the ledger useless for the one question it exists to
			// answer, so the reason is mandatory here even though it is optional elsewhere.
			throw new BadRequestException("An adjustment must carry a reason");
		}
		StockItem item = get(sku);
		int newOnHand = item.getOnHand() + delta;
		if (newOnHand < 0) {
			throw new ConflictException("ADJUSTMENT_BELOW_ZERO",
					"Adjusting %s by %d would take on-hand below zero".formatted(item.getSku(), delta));
		}
		if (newOnHand < item.getReserved()) {
			throw new ConflictException("ADJUSTMENT_BELOW_RESERVED",
					"Adjusting %s by %d would leave %d units on hand against %d already reserved"
							.formatted(item.getSku(), delta, newOnHand, item.getReserved()));
		}
		item.setOnHand(newOnHand);
		flushOrConflict(sku);
		recorder.record(item.getSku(), MovementType.ADJUSTED, delta, 0, null, null, reason);
		gate.invalidate(item.getSku());
		return item;
	}

	private void flushOrConflict(String sku) {
		try {
			stockItems.flush();
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException("STALE_STOCK",
					"Stock for %s was modified concurrently; reload and retry".formatted(sku));
		}
	}

	private String normalise(String sku) {
		if (sku == null || sku.isBlank()) {
			throw new BadRequestException("SKU must not be blank");
		}
		return sku.trim().toUpperCase(Locale.ROOT);
	}
}
