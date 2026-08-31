package com.flashcart.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.inventory.domain.StockItem;

/**
 * @param onHand    physically present, including units held by an unpaid reservation
 * @param reserved  held by a live reservation
 * @param available what a new buyer could still take — the only one of the three a storefront
 *                  should ever render
 */
public record StockResponse(
		UUID id,
		String sku,
		int onHand,
		int reserved,
		int available,
		Long version,
		Instant updatedAt) {

	public static StockResponse from(StockItem item) {
		return new StockResponse(item.getId(), item.getSku(), item.getOnHand(), item.getReserved(),
				item.available(), item.getVersion(), item.getUpdatedAt());
	}
}
