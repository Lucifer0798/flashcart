package com.flashcart.catalog.domain;

/**
 * An admin's <em>intent</em> for a sale. Whether it is live right now is not stored here — see
 * {@link FlashSalePhase}, which is derived from the time window at read time.
 */
public enum FlashSaleStatus {

	/** Being assembled. Not visible, will not go live even if the window opens. */
	DRAFT,

	/** Approved. Goes live on its own when the window opens, with nothing to flip a flag. */
	SCHEDULED,

	/** Called off. Never goes live regardless of the window. */
	CANCELLED
}
