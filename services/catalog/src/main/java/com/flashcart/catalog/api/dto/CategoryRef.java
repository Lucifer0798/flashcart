package com.flashcart.catalog.api.dto;

import java.util.UUID;

import com.flashcart.catalog.domain.Category;

/** Just enough category to render a product row without a second request. */
public record CategoryRef(UUID id, String slug, String name) {

	public static CategoryRef from(Category category) {
		return new CategoryRef(category.getId(), category.getSlug(), category.getName());
	}
}
