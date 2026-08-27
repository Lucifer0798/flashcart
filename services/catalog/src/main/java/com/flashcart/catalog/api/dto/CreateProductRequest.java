package com.flashcart.catalog.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.flashcart.catalog.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param sku        merchant identifier; normalised to upper case and immutable once created,
 *                   because inventory and every historical order key on it
 * @param name       display name
 * @param slug       URL segment; derived from the name when omitted
 * @param categoryId an existing category
 * @param basePrice  list price, two decimal places, non-negative
 * @param currency   ISO-4217 code; defaults to USD when omitted
 * @param status     defaults to DRAFT, so a half-built product is never briefly live
 */
public record CreateProductRequest(
		@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$",
				message = "may contain letters, digits, dot, underscore and hyphen only") String sku,
		@NotBlank @Size(max = 200) String name,
		@Size(max = 160) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lower-case words separated by single hyphens") String slug,
		String description,
		@NotNull UUID categoryId,
		@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
		@Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO-4217 code") String currency,
		ProductStatus status,
		@Size(max = 500) String imageUrl) {
}
