package com.flashcart.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReceiveStockRequest(
		@NotNull @Min(1) Integer quantity,
		@Size(max = 200) String reason) {
}
