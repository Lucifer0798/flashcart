package com.flashcart.inventory.domain;

/** Where a shopper stands in the queue for a sold-out SKU. */
public enum WaitlistStatus {

	/** In the queue. The only state that can be told anything. */
	WAITING,

	/**
	 * Told that units came back. Final: a shopper who was told and missed the unit joins again at the
	 * back, rather than keeping a place they were already given. See ADR 0044.
	 */
	NOTIFIED,

	/** Left the queue before being told. */
	CANCELLED
}
