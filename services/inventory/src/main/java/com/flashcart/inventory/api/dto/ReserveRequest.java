package com.flashcart.inventory.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param reservationKey the caller's idempotency key, normally the order id. Retrying with the same
 *                       key returns the original hold rather than taking a second one, which matters
 *                       because retries are certain rather than hypothetical.
 * @param flashSaleId    set to have the sale's allocation and per-customer cap enforced too; omit
 *                       for ordinary stock
 * @param ttlSeconds     how long to hold for; falls back to the server default and is capped by the
 *                       server maximum
 * @param lines          all-or-nothing: a basket that cannot be fully held takes nothing
 */
public record ReserveRequest(
		@NotBlank @Size(max = 100) String reservationKey,
		@NotBlank @Size(max = 100) String customerId,
		UUID flashSaleId,
		@Min(1) Integer ttlSeconds,
		@NotEmpty @Valid List<Line> lines) {

	public record Line(
			@NotBlank @Size(max = 64) String sku,
			@NotNull @Min(1) Integer quantity) {
	}
}
