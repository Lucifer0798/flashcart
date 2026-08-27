package com.flashcart.catalog.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param discountPercent computed server-side and rounded to a whole percent, so every surface shows
 *                        the same "-40%" badge instead of each client rounding its own way
 */
public record FlashSaleItemResponse(
		UUID id,
		UUID productId,
		String sku,
		String productName,
		BigDecimal basePrice,
		BigDecimal salePrice,
		int discountPercent,
		int allocatedUnits,
		int perCustomerLimit) {
}
