package com.flashcart.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.catalog.domain.Category;

public record CategoryResponse(
		UUID id,
		String slug,
		String name,
		String description,
		Instant createdAt,
		Instant updatedAt) {

	public static CategoryResponse from(Category category) {
		return new CategoryResponse(category.getId(), category.getSlug(), category.getName(),
				category.getDescription(), category.getCreatedAt(), category.getUpdatedAt());
	}
}
