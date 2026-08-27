package com.flashcart.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.flashcart.catalog.api.dto.CategoryRequest;
import com.flashcart.catalog.api.dto.CategoryResponse;
import com.flashcart.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories")
public class CategoryController {

	private final CategoryService categories;

	public CategoryController(CategoryService categories) {
		this.categories = categories;
	}

	@GetMapping
	@Operation(summary = "List all categories, alphabetically")
	public List<CategoryResponse> list() {
		return categories.list();
	}

	@GetMapping("/{idOrSlug}")
	@Operation(summary = "Fetch one category by id or slug")
	public CategoryResponse get(@PathVariable String idOrSlug) {
		return categories.get(idOrSlug);
	}

	@PostMapping
	@Operation(summary = "Create a category")
	public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
		CategoryResponse created = categories.create(request);
		return ResponseEntity.created(URI.create("/api/v1/categories/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Rename a category or change its description")
	public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
		return categories.update(id, request);
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete an empty category", description = "Refused with 409 while it still holds products")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		categories.delete(id);
		return ResponseEntity.noContent().build();
	}
}
