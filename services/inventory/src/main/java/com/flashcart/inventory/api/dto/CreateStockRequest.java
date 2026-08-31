package com.flashcart.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param sku             normalised to upper case; the join key to catalog, which owns the product
 * @param initialQuantity opening balance, defaults to zero
 */
public record CreateStockRequest(
		@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$",
				message = "may contain letters, digits, dot, underscore and hyphen only") String sku,
		@Min(0) int initialQuantity,
		@Size(max = 200) String reason) {
}
