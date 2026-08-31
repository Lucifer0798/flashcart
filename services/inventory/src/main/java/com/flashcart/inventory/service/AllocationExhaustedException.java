package com.flashcart.inventory.service;

import java.util.UUID;

import com.flashcart.common.error.ConflictException;

/**
 * The warehouse has the units, but this flash sale has already sold its whole allocation.
 *
 * <p>Distinct from {@link InsufficientStockException} on purpose: the shopper sees the same "sold
 * out", but operationally these are entirely different situations, and a support ticket about one is
 * not a support ticket about the other.
 */
public class AllocationExhaustedException extends ConflictException {

	public AllocationExhaustedException(UUID flashSaleId, String sku) {
		super("SALE_ALLOCATION_EXHAUSTED",
				"Flash sale %s has sold its entire allocation of %s".formatted(flashSaleId, sku));
	}
}
