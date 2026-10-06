package com.flashcart.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.inventory.domain.WaitlistStatus;
import com.flashcart.inventory.service.WaitlistService;

/**
 * @param ahead how many shoppers are in front of this one for the same SKU; null once the entry is no
 *              longer waiting. Zero means next: the next unit that comes back is this shopper's notice
 */
public record WaitlistEntryResponse(
		UUID id,
		String sku,
		WaitlistStatus status,
		Long ahead,
		Instant joinedAt,
		Instant notifiedAt,
		Instant cancelledAt) {

	public static WaitlistEntryResponse from(WaitlistService.Place place) {
		var entry = place.entry();
		return new WaitlistEntryResponse(entry.getId(), entry.getSku(), entry.getStatus(), place.ahead(),
				entry.getCreatedAt(), entry.getNotifiedAt(), entry.getCancelledAt());
	}
}
