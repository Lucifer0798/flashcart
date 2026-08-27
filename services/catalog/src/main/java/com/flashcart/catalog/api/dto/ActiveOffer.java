package com.flashcart.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The live flash-sale terms attached to a product, or absent when it is selling at list price.
 *
 * @param perCustomerLimit the anti-scalper cap the order service will enforce
 * @param endsAt           when this price stops applying; clients use it to render a countdown
 */
public record ActiveOffer(
		UUID flashSaleId,
		String flashSaleSlug,
		String flashSaleName,
		BigDecimal salePrice,
		int perCustomerLimit,
		int allocatedUnits,
		Instant endsAt) {
}
