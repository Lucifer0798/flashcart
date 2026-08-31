package com.flashcart.inventory.api.dto;

import jakarta.validation.constraints.Min;

/** Both fields optional; only what is supplied changes. */
public record UpdateAllocationRequest(
		@Min(1) Integer allocatedUnits,
		@Min(1) Integer perCustomerLimit) {
}
