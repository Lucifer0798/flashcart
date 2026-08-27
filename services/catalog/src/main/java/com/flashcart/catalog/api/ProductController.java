package com.flashcart.catalog.api;

import java.net.URI;
import java.util.UUID;

import com.flashcart.catalog.api.dto.CreateProductRequest;
import com.flashcart.catalog.api.dto.ProductResponse;
import com.flashcart.catalog.api.dto.UpdateProductRequest;
import com.flashcart.catalog.domain.ProductStatus;
import com.flashcart.catalog.service.ProductService;
import com.flashcart.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {

	/**
	 * Hard ceiling on page size. Without one, a single {@code ?size=100000} is enough to turn the
	 * listing into a denial of service on the busiest endpoint in the platform.
	 */
	private static final int MAX_PAGE_SIZE = 100;

	private final ProductService products;

	public ProductController(ProductService products) {
		this.products = products;
	}

	@GetMapping
	@Operation(summary = "List products",
			description = "Every row carries an effectivePrice that already accounts for any live flash sale.")
	public PageResponse<ProductResponse> list(
			@RequestParam(required = false) ProductStatus status,
			@RequestParam(required = false) UUID categoryId,
			@RequestParam(required = false) String categorySlug,
			@Parameter(description = "Substring match over name and SKU") @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
			@Parameter(description = "Property to sort by, e.g. name or basePrice")
			@RequestParam(defaultValue = "createdAt") String sort,
			@RequestParam(defaultValue = "desc") String direction) {

		Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
		PageRequest pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by(dir, sort));
		return products.list(status, categoryId, categorySlug, q, pageable);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Fetch one product by id")
	public ProductResponse get(@PathVariable UUID id) {
		return products.get(id);
	}

	@GetMapping("/sku/{sku}")
	@Operation(summary = "Fetch one product by SKU",
			description = "The lookup other services use, since SKU is what orders and inventory hold.")
	public ProductResponse getBySku(@PathVariable String sku) {
		return products.getBySku(sku);
	}

	@GetMapping("/slug/{slug}")
	@Operation(summary = "Fetch one product by URL slug")
	public ProductResponse getBySlug(@PathVariable String slug) {
		return products.getBySlug(slug);
	}

	@PostMapping
	@Operation(summary = "Create a product", description = "Defaults to DRAFT so it is not immediately live.")
	public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
		ProductResponse created = products.create(request);
		return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Replace a product's mutable fields",
			description = "Send expectedVersion to be told (409) rather than silently overwrite a concurrent edit.")
	public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
		return products.update(id, request);
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Archive a product",
			description = "Archives rather than deletes: historical orders still reference it.")
	public ProductResponse archive(@PathVariable UUID id) {
		return products.archive(id);
	}
}
