package com.flashcart.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.flashcart.catalog.domain.ProductStatus;

/**
 * @param basePrice      list price
 * @param effectivePrice what a shopper pays right now — the sale price when an offer is live,
 *                       otherwise the base price. Clients should render this and never recompute it,
 *                       so the discount rules live in exactly one place.
 * @param offer          the live offer behind {@code effectivePrice}, absent when there is none
 * @param version        optimistic-lock version, to hand back on update
 */
public record ProductResponse(
		UUID id,
		String sku,
		String slug,
		String name,
		String description,
		CategoryRef category,
		BigDecimal basePrice,
		BigDecimal effectivePrice,
		String currency,
		ProductStatus status,
		String imageUrl,
		boolean onFlashSale,
		ActiveOffer offer,
		Long version,
		Instant createdAt,
		Instant updatedAt) {
}
