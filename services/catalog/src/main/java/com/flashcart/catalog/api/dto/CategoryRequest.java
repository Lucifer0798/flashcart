package com.flashcart.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param name        display name, required
 * @param slug        URL segment; derived from the name when omitted
 * @param description optional merchandising copy
 */
public record CategoryRequest(
		@NotBlank @Size(max = 160) String name,
		@Size(max = 120) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lower-case words separated by single hyphens") String slug,
		String description) {
}
