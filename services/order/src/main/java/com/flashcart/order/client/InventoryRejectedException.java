package com.flashcart.order.client;

import com.flashcart.common.error.ConflictException;

/**
 * Inventory said no, and meant it.
 *
 * <p>Carries inventory's own code — {@code INSUFFICIENT_STOCK},
 * {@code SALE_ALLOCATION_EXHAUSTED}, {@code CUSTOMER_LIMIT_EXCEEDED} — straight through to the
 * caller rather than flattening them into one message. All three look like "sold out" to a shopper
 * and mean entirely different things to whoever is on support.
 *
 * <p>Distinct from {@link InventoryUnavailableException} because the difference is decisive: a
 * refusal is final and the order can be cancelled, whereas an unavailable inventory leaves the hold
 * in an unknown state.
 */
public class InventoryRejectedException extends ConflictException {

	public InventoryRejectedException(String code, String message) {
		super(code == null ? "INVENTORY_REJECTED" : code, message);
	}
}
