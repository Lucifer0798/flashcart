package com.flashcart.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.flashcart.inventory.domain.WaitlistStatus;
import com.flashcart.inventory.service.WaitlistService;

/**
 * @param ahead how many shoppers are in front of this one for the same SKU; null once the entry is no
 *              longer waiting. Zero means next: the next unit that comes back is this shopper's notice
 * @param heldUntil until when a unit is held for this shopper after being told; null when nothing is
 *                  held -- not yet told, already checked out, or the hold lapsed. See ADR 0045
 */
public record WaitlistEntryResponse(
		UUID id,
		String sku,
		WaitlistStatus status,
		Long ahead,
		Instant joinedAt,
		Instant notifiedAt,
		Instant heldUntil,
		Instant cancelledAt) {

	public static WaitlistEntryResponse from(WaitlistService.Place place) {
		var entry = place.entry();
		return new WaitlistEntryResponse(entry.getId(), entry.getSku(), entry.getStatus(), place.ahead(),
				entry.getCreatedAt(), entry.getNotifiedAt(), place.heldUntil(), entry.getCancelledAt());
	}
}
