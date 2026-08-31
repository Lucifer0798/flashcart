package com.flashcart.inventory.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.inventory.domain.SaleAllocation;
import com.flashcart.inventory.domain.StockItem;
import com.flashcart.inventory.repository.SaleAllocationRepository;
import com.flashcart.inventory.repository.StockItemRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers how much of a SKU a flash sale may sell.
 *
 * <p>Catalog is the owner of the sale's <em>definition</em> — which products, at what price, with
 * what per-customer cap. This service takes that as an instruction and becomes the thing that
 * actually enforces it. Until Phase 5 puts events between them, an allocation is registered here
 * explicitly rather than by calling catalog: a synchronous dependency from the most contended
 * service to another one is exactly the coupling worth not having.
 */
@Service
@Transactional(readOnly = true)
public class AllocationService {

	private final SaleAllocationRepository allocations;
	private final StockItemRepository stockItems;

	public AllocationService(SaleAllocationRepository allocations, StockItemRepository stockItems) {
		this.allocations = allocations;
		this.stockItems = stockItems;
	}

	public List<SaleAllocation> forSale(UUID flashSaleId) {
		return allocations.findByFlashSaleId(flashSaleId);
	}

	public SaleAllocation get(UUID flashSaleId, String sku) {
		return allocations.findByFlashSaleIdAndSku(flashSaleId, normalise(sku))
				.orElseThrow(() -> ResourceNotFoundException
						.of("Allocation for sale %s and SKU".formatted(flashSaleId), sku));
	}

	@Transactional
	public SaleAllocation create(UUID flashSaleId, String sku, int allocatedUnits, int perCustomerLimit) {
		String normalized = normalise(sku);
		if (allocatedUnits <= 0) {
			throw new BadRequestException("Allocated units must be positive");
		}
		if (perCustomerLimit <= 0) {
			throw new BadRequestException("Per-customer limit must be positive");
		}

		StockItem item = stockItems.findBySku(normalized)
				.orElseThrow(() -> ResourceNotFoundException.of("Stock for SKU", sku));
		// A sanity check, not an invariant: stock can legitimately arrive after the sale is set up,
		// so this warns about the obvious mistake without forbidding a valid sequence.
		if (allocatedUnits > item.getOnHand()) {
			throw new ConflictException("ALLOCATION_EXCEEDS_STOCK",
					"Cannot allocate %d units of %s to the sale; only %d are on hand"
							.formatted(allocatedUnits, normalized, item.getOnHand()));
		}
		if (allocations.existsByFlashSaleIdAndSku(flashSaleId, normalized)) {
			throw new ConflictException("ALLOCATION_EXISTS",
					"Flash sale %s already has an allocation for %s".formatted(flashSaleId, normalized));
		}

		SaleAllocation allocation = new SaleAllocation(UUID.randomUUID(), flashSaleId, normalized,
				allocatedUnits, perCustomerLimit);
		try {
			return allocations.saveAndFlush(allocation);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("ALLOCATION_EXISTS",
					"Flash sale %s already has an allocation for %s".formatted(flashSaleId, normalized));
		}
	}

	/**
	 * Change an allocation's size or cap.
	 *
	 * <p>Shrinking below what the sale has already consumed is refused: those units are held or sold,
	 * and the database's {@code reserved + committed <= allocated} check would reject it regardless.
	 */
	@Transactional
	public SaleAllocation update(UUID flashSaleId, String sku, Integer allocatedUnits, Integer perCustomerLimit) {
		SaleAllocation allocation = get(flashSaleId, sku);
		if (allocatedUnits != null) {
			int consumed = allocation.getReservedUnits() + allocation.getCommittedUnits();
			if (allocatedUnits < consumed) {
				throw new ConflictException("ALLOCATION_BELOW_CONSUMED",
						"Cannot shrink the allocation to %d; %d units are already held or sold"
								.formatted(allocatedUnits, consumed));
			}
			allocation.setAllocatedUnits(allocatedUnits);
		}
		if (perCustomerLimit != null) {
			if (perCustomerLimit <= 0) {
				throw new BadRequestException("Per-customer limit must be positive");
			}
			allocation.setPerCustomerLimit(perCustomerLimit);
		}
		return allocation;
	}

	private String normalise(String sku) {
		if (sku == null || sku.isBlank()) {
			throw new BadRequestException("SKU must not be blank");
		}
		return sku.trim().toUpperCase(Locale.ROOT);
	}
}
