package com.flashcart.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param delta  signed; negative for damage or shrinkage
 * @param reason mandatory, because an unexplained adjustment makes the ledger useless for the one
 *               question it exists to answer
 */
public record AdjustStockRequest(
		@NotNull Integer delta,
		@NotBlank @Size(max = 200) String reason) {
}
