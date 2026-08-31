package com.flashcart.inventory.service;

import com.flashcart.common.error.ConflictException;

/**
 * The units are not there.
 *
 * <p>Its own type with its own code because this is the single most common outcome during a flash
 * sale and callers must branch on it precisely — a shopper seeing "sold out" is a normal day, and it
 * must never be confused with the several other things that can make a reservation fail.
 */
public class InsufficientStockException extends ConflictException {

	private final String sku;

	public InsufficientStockException(String sku, int requested) {
		super("INSUFFICIENT_STOCK", "Only fewer than %d units of %s are available".formatted(requested, sku));
		this.sku = sku;
	}

	public String getSku() {
		return sku;
	}
}
