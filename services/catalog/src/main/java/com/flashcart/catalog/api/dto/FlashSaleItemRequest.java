package com.flashcart.catalog.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param allocatedUnits   how many units this sale may sell
 * @param perCustomerLimit anti-scalper cap; defaults to 1 when omitted
 */
public record FlashSaleItemRequest(
		@NotNull UUID productId,
		@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal salePrice,
		@NotNull @Min(1) Integer allocatedUnits,
		@Min(1) Integer perCustomerLimit) {
}
