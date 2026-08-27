package com.flashcart.catalog.service;

import java.util.List;
import java.util.UUID;

import com.flashcart.catalog.api.dto.CategoryRequest;
import com.flashcart.catalog.api.dto.CategoryResponse;
import com.flashcart.catalog.domain.Category;
import com.flashcart.catalog.repository.CategoryRepository;
import com.flashcart.catalog.repository.ProductRepository;
import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {

	private final CategoryRepository categories;
	private final ProductRepository products;

	public CategoryService(CategoryRepository categories, ProductRepository products) {
		this.categories = categories;
		this.products = products;
	}

	public List<CategoryResponse> list() {
		return categories.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
	}

	public CategoryResponse get(String idOrSlug) {
		return CategoryResponse.from(require(idOrSlug));
	}

	@Transactional
	public CategoryResponse create(CategoryRequest request) {
		String slug = Slugs.orDerive(request.slug(), request.name());
		if (slug.isEmpty()) {
			throw new BadRequestException("Category name must contain at least one letter or digit");
		}
		if (categories.existsBySlug(slug)) {
			throw new ConflictException("CATEGORY_SLUG_TAKEN", "A category with slug '%s' already exists".formatted(slug));
		}
		Category category = new Category(UUID.randomUUID(), slug, request.name(), request.description());
		try {
			return CategoryResponse.from(categories.saveAndFlush(category));
		}
		catch (DataIntegrityViolationException ex) {
			// The existsBySlug check above is a courtesy, not a guarantee: two concurrent creates can
			// both pass it. The unique constraint is the real defence, and this turns it into a 409.
			throw new ConflictException("CATEGORY_SLUG_TAKEN", "A category with slug '%s' already exists".formatted(slug));
		}
	}

	@Transactional
	public CategoryResponse update(UUID id, CategoryRequest request) {
		Category category = categories.findById(id)
				.orElseThrow(() -> ResourceNotFoundException.of("Category", id));
		category.setName(request.name());
		category.setDescription(request.description());
		return CategoryResponse.from(category);
	}

	@Transactional
	public void delete(UUID id) {
		Category category = categories.findById(id)
				.orElseThrow(() -> ResourceNotFoundException.of("Category", id));
		if (products.existsByCategoryId(id)) {
			// Refuse rather than cascade. Products outlive merchandising decisions, and silently
			// deleting a category's products would take live listings down with it.
			throw new ConflictException("CATEGORY_NOT_EMPTY",
					"Category '%s' still has products; move or archive them first".formatted(category.getSlug()));
		}
		categories.delete(category);
	}

	/** Resolves a path segment that may be either a UUID or a slug. */
	Category require(String idOrSlug) {
		return parseUuid(idOrSlug)
				.flatMap(categories::findById)
				.or(() -> categories.findBySlug(idOrSlug.toLowerCase()))
				.orElseThrow(() -> ResourceNotFoundException.of("Category", idOrSlug));
	}

	Category requireById(UUID id) {
		return categories.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Category", id));
	}

	static java.util.Optional<UUID> parseUuid(String value) {
		try {
			return java.util.Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException ex) {
			return java.util.Optional.empty();
		}
	}
}
