package com.flashcart.catalog.api.dto;

import java.time.Instant;

import com.flashcart.catalog.domain.FlashSaleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param status defaults to DRAFT. A sale only goes live on its own once it is SCHEDULED and its
 *               window opens, so creating one can never accidentally start selling.
 */
public record FlashSaleRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 160) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lower-case words separated by single hyphens") String slug,
		@NotNull Instant startsAt,
		@NotNull Instant endsAt,
		FlashSaleStatus status) {
}
