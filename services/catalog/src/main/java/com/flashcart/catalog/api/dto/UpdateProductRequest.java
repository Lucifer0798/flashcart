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
 * A full replacement of the mutable fields. The SKU is absent on purpose: it is the identifier other
 * services and past orders hold, so it is not editable.
 *
 * @param expectedVersion the version the client last read. When supplied it is checked before the
 *                        write, turning a silent lost update into a 409.
 */
public record UpdateProductRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 160) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lower-case words separated by single hyphens") String slug,
		String description,
		@NotNull UUID categoryId,
		@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
		@Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO-4217 code") String currency,
		@NotNull ProductStatus status,
		@Size(max = 500) String imageUrl,
		Long expectedVersion) {
}
