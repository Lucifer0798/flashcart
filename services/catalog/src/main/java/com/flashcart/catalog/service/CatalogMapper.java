package com.flashcart.catalog.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.flashcart.catalog.api.dto.ActiveOffer;
import com.flashcart.catalog.api.dto.CategoryRef;
import com.flashcart.catalog.api.dto.FlashSaleItemResponse;
import com.flashcart.catalog.api.dto.ProductResponse;
import com.flashcart.catalog.domain.FlashSaleItem;
import com.flashcart.catalog.domain.Product;

/** Entity-to-response translation, kept out of the services so they stay about rules, not shape. */
public final class CatalogMapper {

	private CatalogMapper() {
	}

	/**
	 * @param liveOffer the live flash-sale offer for this product, or {@code null} when it is
	 *                  selling at list price
	 */
	public static ProductResponse toResponse(Product product, FlashSaleItem liveOffer) {
		BigDecimal effectivePrice = liveOffer != null ? liveOffer.getSalePrice() : product.getBasePrice();
		ActiveOffer offer = liveOffer == null ? null : new ActiveOffer(
				liveOffer.getFlashSale().getId(),
				liveOffer.getFlashSale().getSlug(),
				liveOffer.getFlashSale().getName(),
				liveOffer.getSalePrice(),
				liveOffer.getPerCustomerLimit(),
				liveOffer.getAllocatedUnits(),
				liveOffer.getFlashSale().getEndsAt());

		return new ProductResponse(
				product.getId(),
				product.getSku(),
				product.getSlug(),
				product.getName(),
				product.getDescription(),
				CategoryRef.from(product.getCategory()),
				product.getBasePrice(),
				effectivePrice,
				product.getCurrency(),
				product.getStatus(),
				product.getImageUrl(),
				liveOffer != null,
				offer,
				product.getVersion(),
				product.getCreatedAt(),
				product.getUpdatedAt());
	}

	public static FlashSaleItemResponse toResponse(FlashSaleItem item) {
		Product product = item.getProduct();
		return new FlashSaleItemResponse(
				item.getId(),
				product.getId(),
				product.getSku(),
				product.getName(),
				product.getBasePrice(),
				item.getSalePrice(),
				discountPercent(product.getBasePrice(), item.getSalePrice()),
				item.getAllocatedUnits(),
				item.getPerCustomerLimit());
	}

	/**
	 * Whole-percent discount, computed once here so every client renders the same badge instead of
	 * each rounding its own way.
	 */
	static int discountPercent(BigDecimal basePrice, BigDecimal salePrice) {
		if (basePrice == null || salePrice == null || basePrice.signum() <= 0) {
			return 0;
		}
		BigDecimal saved = basePrice.subtract(salePrice);
		if (saved.signum() <= 0) {
			return 0;
		}
		return saved.multiply(BigDecimal.valueOf(100))
				.divide(basePrice, 0, RoundingMode.HALF_UP)
				.intValue();
	}
}
