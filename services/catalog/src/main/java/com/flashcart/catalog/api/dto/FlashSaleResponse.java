package com.flashcart.catalog.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.catalog.domain.FlashSalePhase;
import com.flashcart.catalog.domain.FlashSaleStatus;

/**
 * @param status the admin's intent, as stored
 * @param phase  where the sale sits against the clock right now — derived on every read, never
 *               stored, so it cannot go stale
 */
public record FlashSaleResponse(
		UUID id,
		String slug,
		String name,
		Instant startsAt,
		Instant endsAt,
		FlashSaleStatus status,
		FlashSalePhase phase,
		List<FlashSaleItemResponse> items,
		Instant createdAt,
		Instant updatedAt) {
}
