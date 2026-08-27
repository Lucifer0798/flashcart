package com.flashcart.catalog.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.flashcart.catalog.api.dto.CreateProductRequest;
import com.flashcart.catalog.api.dto.ProductResponse;
import com.flashcart.catalog.api.dto.UpdateProductRequest;
import com.flashcart.catalog.domain.Category;
import com.flashcart.catalog.domain.FlashSaleItem;
import com.flashcart.catalog.domain.Product;
import com.flashcart.catalog.domain.ProductStatus;
import com.flashcart.catalog.repository.ProductRepository;
import com.flashcart.catalog.repository.ProductSpecifications;
import com.flashcart.common.api.PageResponse;
import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

	private static final String DEFAULT_CURRENCY = "USD";

	private final ProductRepository products;
	private final CategoryService categories;
	private final PricingService pricing;

	public ProductService(ProductRepository products, CategoryService categories, PricingService pricing) {
		this.products = products;
		this.categories = categories;
		this.pricing = pricing;
	}

	/**
	 * The storefront listing.
	 *
	 * <p>Prices the whole page in one extra query rather than one per row — see
	 * {@link PricingService#liveOffersByProduct}.
	 */
	public PageResponse<ProductResponse> list(ProductStatus status, UUID categoryId, String categorySlug, String q,
			Pageable pageable) {
		Specification<Product> spec = Specification.allOf(
				ProductSpecifications.hasStatus(status),
				ProductSpecifications.inCategory(categoryId),
				ProductSpecifications.inCategorySlug(categorySlug),
				ProductSpecifications.matching(q));

		Page<Product> page = products.findAll(spec, pageable);
		Map<UUID, FlashSaleItem> offers = pricing.liveOffersByProduct(page.getContent().stream()
				.map(Product::getId)
				.toList());

		List<ProductResponse> content = page.getContent().stream()
				.map(product -> CatalogMapper.toResponse(product, offers.get(product.getId())))
				.toList();

		return PageResponse.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
	}

	public ProductResponse get(UUID id) {
		return priced(products.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Product", id)));
	}

	public ProductResponse getBySku(String sku) {
		String normalized = sku.toUpperCase(Locale.ROOT);
		return priced(products.findBySku(normalized)
				.orElseThrow(() -> ResourceNotFoundException.of("Product with SKU", sku)));
	}

	public ProductResponse getBySlug(String slug) {
		return priced(products.findBySlug(slug.toLowerCase(Locale.ROOT))
				.orElseThrow(() -> ResourceNotFoundException.of("Product with slug", slug)));
	}

	@Transactional
	public ProductResponse create(CreateProductRequest request) {
		String sku = request.sku().toUpperCase(Locale.ROOT);
		String slug = Slugs.orDerive(request.slug(), request.name());
		if (slug.isEmpty()) {
			throw new BadRequestException("Product name must contain at least one letter or digit");
		}
		if (products.existsBySku(sku)) {
			throw new ConflictException("SKU_TAKEN", "A product with SKU '%s' already exists".formatted(sku));
		}
		if (products.existsBySlug(slug)) {
			throw new ConflictException("PRODUCT_SLUG_TAKEN", "A product with slug '%s' already exists".formatted(slug));
		}
		Category category = categories.requireById(request.categoryId());

		Product product = new Product(
				UUID.randomUUID(),
				sku,
				slug,
				request.name(),
				request.description(),
				category,
				request.basePrice(),
				request.currency() == null ? DEFAULT_CURRENCY : request.currency(),
				// Defaults to DRAFT so a half-built product is never briefly live on the storefront.
				request.status() == null ? ProductStatus.DRAFT : request.status(),
				request.imageUrl());

		try {
			return priced(products.saveAndFlush(product));
		}
		catch (DataIntegrityViolationException ex) {
			// Two concurrent creates can both pass the exists checks above; the unique constraints
			// are the real defence and this renders them as a 409 rather than a 500.
			throw new ConflictException("PRODUCT_ALREADY_EXISTS",
					"A product with SKU '%s' or slug '%s' already exists".formatted(sku, slug));
		}
	}

	@Transactional
	public ProductResponse update(UUID id, UpdateProductRequest request) {
		Product product = products.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Product", id));

		// An explicit precondition check, so a stale editor gets a clear 409 up front instead of
		// finding out at flush time. The @Version column still guards the gap between here and commit.
		if (request.expectedVersion() != null && !request.expectedVersion().equals(product.getVersion())) {
			throw new ConflictException("STALE_PRODUCT",
					"Product %s has changed since you loaded it (expected version %d, found %d)"
							.formatted(id, request.expectedVersion(), product.getVersion()));
		}

		String slug = Slugs.orDerive(request.slug(), request.name());
		if (!slug.equals(product.getSlug()) && products.existsBySlug(slug)) {
			throw new ConflictException("PRODUCT_SLUG_TAKEN", "A product with slug '%s' already exists".formatted(slug));
		}

		product.setName(request.name());
		product.setSlug(slug);
		product.setDescription(request.description());
		product.setCategory(categories.requireById(request.categoryId()));
		product.setBasePrice(request.basePrice());
		if (request.currency() != null) {
			product.setCurrency(request.currency());
		}
		product.setStatus(request.status());
		product.setImageUrl(request.imageUrl());

		try {
			products.flush();
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException("STALE_PRODUCT",
					"Product %s was modified concurrently; reload and retry".formatted(id));
		}
		return priced(product);
	}

	/**
	 * Archives rather than deletes. Orders placed months ago still reference this product, and a
	 * dangling id would break every historical order view.
	 */
	@Transactional
	public ProductResponse archive(UUID id) {
		Product product = products.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Product", id));
		product.setStatus(ProductStatus.ARCHIVED);
		return priced(product);
	}

	private ProductResponse priced(Product product) {
		return CatalogMapper.toResponse(product, pricing.liveOfferFor(product.getId()).orElse(null));
	}
}
