package com.flashcart.inventory.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param allocatedUnits   how many units this sale may sell, whatever the warehouse holds
 * @param perCustomerLimit the anti-scalper cap; defaults to 1
 */
public record AllocationRequest(
		@NotNull UUID flashSaleId,
		@NotBlank @Size(max = 64) String sku,
		@NotNull @Min(1) Integer allocatedUnits,
		@Min(1) Integer perCustomerLimit) {
}
