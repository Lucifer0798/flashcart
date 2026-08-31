package com.flashcart.inventory.service;

import com.flashcart.common.error.ConflictException;

/** The customer has hit the per-customer cap for this SKU in this sale. */
public class CustomerLimitExceededException extends ConflictException {

	public CustomerLimitExceededException(String sku, int limit) {
		super("CUSTOMER_LIMIT_EXCEEDED",
				"This sale allows at most %d unit(s) of %s per customer".formatted(limit, sku));
	}
}
