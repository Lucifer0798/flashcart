package com.flashcart.catalog.service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.catalog.api.dto.FlashSaleItemRequest;
import com.flashcart.catalog.api.dto.FlashSaleItemResponse;
import com.flashcart.catalog.api.dto.FlashSaleRequest;
import com.flashcart.catalog.api.dto.FlashSaleResponse;
import com.flashcart.catalog.domain.FlashSale;
import com.flashcart.catalog.domain.FlashSaleItem;
import com.flashcart.catalog.domain.FlashSalePhase;
import com.flashcart.catalog.domain.FlashSaleStatus;
import com.flashcart.catalog.domain.Product;
import com.flashcart.catalog.domain.ProductStatus;
import com.flashcart.catalog.repository.FlashSaleItemRepository;
import com.flashcart.catalog.repository.FlashSaleRepository;
import com.flashcart.catalog.repository.ProductRepository;
import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FlashSaleService {

	private final FlashSaleRepository flashSales;
	private final FlashSaleItemRepository flashSaleItems;
	private final ProductRepository products;
	private final Clock clock;

	public FlashSaleService(FlashSaleRepository flashSales, FlashSaleItemRepository flashSaleItems,
			ProductRepository products, Clock clock) {
		this.flashSales = flashSales;
		this.flashSaleItems = flashSaleItems;
		this.products = products;
		this.clock = clock;
	}

	/** Sales live at this instant — the storefront's "on now" rail. */
	public List<FlashSaleResponse> live() {
		return flashSales.findLive(clock.instant()).stream().map(this::toResponse).toList();
	}

	public List<FlashSaleResponse> upcoming() {
		return flashSales.findUpcoming(clock.instant()).stream().map(this::toResponse).toList();
	}

	public List<FlashSaleResponse> all() {
		return flashSales.findAll().stream().map(this::toResponse).toList();
	}

	public FlashSaleResponse get(String idOrSlug) {
		return toResponse(require(idOrSlug));
	}

	@Transactional
	public FlashSaleResponse create(FlashSaleRequest request) {
		if (!request.endsAt().isAfter(request.startsAt())) {
			throw new BadRequestException("A flash sale must end after it starts");
		}
		String slug = Slugs.orDerive(request.slug(), request.name());
		if (slug.isEmpty()) {
			throw new BadRequestException("Flash sale name must contain at least one letter or digit");
		}
		if (flashSales.existsBySlug(slug)) {
			throw new ConflictException("FLASH_SALE_SLUG_TAKEN",
					"A flash sale with slug '%s' already exists".formatted(slug));
		}

		FlashSale sale = new FlashSale(UUID.randomUUID(), slug, request.name(), request.startsAt(), request.endsAt(),
				// DRAFT by default: creating a sale must never be the thing that starts selling.
				request.status() == null ? FlashSaleStatus.DRAFT : request.status());
		try {
			return toResponse(flashSales.saveAndFlush(sale));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("FLASH_SALE_SLUG_TAKEN",
					"A flash sale with slug '%s' already exists".formatted(slug));
		}
	}

	@Transactional
	public FlashSaleItemResponse addItem(UUID flashSaleId, FlashSaleItemRequest request) {
		FlashSale sale = flashSales.findById(flashSaleId)
				.orElseThrow(() -> ResourceNotFoundException.of("Flash sale", flashSaleId));

		// Changing the line-up of a sale that is already selling would move the price under
		// shoppers mid-session. Compose the sale first, then schedule it.
		if (sale.phase(clock) == FlashSalePhase.ACTIVE) {
			throw new ConflictException("FLASH_SALE_LIVE",
					"Flash sale '%s' is live; its items can no longer be changed".formatted(sale.getSlug()));
		}
		if (sale.getStatus() == FlashSaleStatus.CANCELLED) {
			throw new ConflictException("FLASH_SALE_CANCELLED",
					"Flash sale '%s' is cancelled".formatted(sale.getSlug()));
		}

		Product product = products.findById(request.productId())
				.orElseThrow(() -> ResourceNotFoundException.of("Product", request.productId()));
		if (product.getStatus() == ProductStatus.ARCHIVED) {
			throw new ConflictException("PRODUCT_ARCHIVED",
					"Product %s is archived and cannot be put on sale".formatted(product.getSku()));
		}
		// A "sale" price at or above list is not a sale. Rejecting it here keeps the effective-price
		// rule a plain "offer wins", with no min() special case to explain at checkout.
		if (request.salePrice().compareTo(product.getBasePrice()) >= 0) {
			throw new BadRequestException("SALE_PRICE_NOT_A_DISCOUNT",
					"Sale price %s must be below the base price %s for %s"
							.formatted(request.salePrice(), product.getBasePrice(), product.getSku()));
		}
		if (flashSaleItems.existsByFlashSaleIdAndProductId(flashSaleId, product.getId())) {
			throw new ConflictException("PRODUCT_ALREADY_IN_SALE",
					"Product %s is already in flash sale '%s'".formatted(product.getSku(), sale.getSlug()));
		}

		FlashSaleItem item = new FlashSaleItem(UUID.randomUUID(), product, request.salePrice(),
				request.allocatedUnits(),
				request.perCustomerLimit() == null ? 1 : request.perCustomerLimit());
		sale.addItem(item);
		try {
			flashSales.flush();
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("PRODUCT_ALREADY_IN_SALE",
					"Product %s is already in flash sale '%s'".formatted(product.getSku(), sale.getSlug()));
		}
		return CatalogMapper.toResponse(item);
	}

	@Transactional
	public void removeItem(UUID flashSaleId, UUID itemId) {
		FlashSale sale = flashSales.findById(flashSaleId)
				.orElseThrow(() -> ResourceNotFoundException.of("Flash sale", flashSaleId));
		if (sale.phase(clock) == FlashSalePhase.ACTIVE) {
			throw new ConflictException("FLASH_SALE_LIVE",
					"Flash sale '%s' is live; its items can no longer be changed".formatted(sale.getSlug()));
		}
		boolean removed = sale.getItems().removeIf(item -> item.getId().equals(itemId));
		if (!removed) {
			throw ResourceNotFoundException.of("Flash sale item", itemId);
		}
	}

	/** Moves a DRAFT sale to SCHEDULED, after which it goes live on its own when the window opens. */
	@Transactional
	public FlashSaleResponse schedule(UUID flashSaleId) {
		FlashSale sale = flashSales.findById(flashSaleId)
				.orElseThrow(() -> ResourceNotFoundException.of("Flash sale", flashSaleId));
		if (sale.getStatus() == FlashSaleStatus.CANCELLED) {
			throw new ConflictException("FLASH_SALE_CANCELLED",
					"Flash sale '%s' is cancelled and cannot be scheduled".formatted(sale.getSlug()));
		}
		if (sale.getItems().isEmpty()) {
			throw new ConflictException("FLASH_SALE_EMPTY",
					"Flash sale '%s' has no items to sell".formatted(sale.getSlug()));
		}
		if (!sale.getEndsAt().isAfter(clock.instant())) {
			throw new ConflictException("FLASH_SALE_WINDOW_PASSED",
					"Flash sale '%s' ended at %s".formatted(sale.getSlug(), sale.getEndsAt()));
		}
		sale.setStatus(FlashSaleStatus.SCHEDULED);
		return toResponse(sale);
	}

	/** Pulls a sale, live or not. Prices revert to list on the next read; nothing has to be swept. */
	@Transactional
	public FlashSaleResponse cancel(UUID flashSaleId) {
		FlashSale sale = flashSales.findById(flashSaleId)
				.orElseThrow(() -> ResourceNotFoundException.of("Flash sale", flashSaleId));
		sale.setStatus(FlashSaleStatus.CANCELLED);
		return toResponse(sale);
	}

	FlashSale require(String idOrSlug) {
		return parseUuid(idOrSlug)
				.flatMap(flashSales::findById)
				.or(() -> flashSales.findBySlug(idOrSlug.toLowerCase(Locale.ROOT)))
				.orElseThrow(() -> ResourceNotFoundException.of("Flash sale", idOrSlug));
	}

	private FlashSaleResponse toResponse(FlashSale sale) {
		List<FlashSaleItemResponse> items = flashSaleItems.findByFlashSaleIdWithProduct(sale.getId()).stream()
				.map(CatalogMapper::toResponse)
				.toList();
		return new FlashSaleResponse(sale.getId(), sale.getSlug(), sale.getName(), sale.getStartsAt(),
				sale.getEndsAt(), sale.getStatus(), sale.phase(clock), items, sale.getCreatedAt(),
				sale.getUpdatedAt());
	}

	private static Optional<UUID> parseUuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
}
