package com.flashcart.order.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param idempotencyKey the caller's key. A double-tapped button or a client retry returns the
 *                       original order instead of placing a second one — and during a flash sale,
 *                       impatient double-taps are the norm rather than the exception.
 * @param flashSaleId    set to have inventory enforce the sale's allocation and per-customer cap
 * @param lines          note there is no price field, deliberately: prices come from catalog, never
 *                       from the request
 */
public record PlaceOrderRequest(
		@NotBlank @Size(max = 100) String idempotencyKey,
		@NotBlank @Size(max = 100) String customerId,
		UUID flashSaleId,
		@NotEmpty @Valid List<Line> lines) {

	public record Line(
			@NotBlank @Size(max = 64) String sku,
			@NotNull @Min(1) Integer quantity) {
	}
}
