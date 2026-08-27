package com.flashcart.catalog.domain;

/**
 * Where a sale sits relative to now — derived, never stored.
 *
 * <p>The alternative, a stored flag flipped by a scheduler, means every second the scheduler is late
 * is a second the storefront sells at the wrong price. Deriving it makes "is this sale live" a pure
 * function of the row and the clock, which is also what makes it trivially cacheable later.
 */
public enum FlashSalePhase {

	DRAFT,
	UPCOMING,
	ACTIVE,
	ENDED,
	CANCELLED
}
