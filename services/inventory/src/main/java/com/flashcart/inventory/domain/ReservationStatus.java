package com.flashcart.inventory.domain;

/** The lifecycle of a hold on stock. */
public enum ReservationStatus {

	/** Stock is held and the clock is running. The only state that keeps units out of circulation. */
	HELD,

	/** Payment landed. Units have left the warehouse; the hold is now a sale. */
	COMMITTED,

	/** Explicitly given back before the timer ran out — the order was cancelled or payment declined. */
	RELEASED,

	/** The timer won. Units went back into circulation without anyone asking. */
	EXPIRED,

	/**
	 * Was {@link #COMMITTED}, and the sale was undone: a paid order was cancelled and shipping
	 * confirmed the goods never left. The units are back on the shelf. See ADR 0040.
	 */
	RETURNED,

	/**
	 * A waitlist hold that the customer's own checkout took over: the unit held for them became part of
	 * their order's reservation. Terminal, and distinct from {@link #RELEASED} because nothing came back
	 * into circulation. See ADR 0045.
	 */
	ADOPTED;

	/** True while this reservation is still holding units out of circulation. */
	public boolean isHolding() {
		return this == HELD;
	}

	/** True when no further transition is possible. */
	public boolean isTerminal() {
		return this != HELD;
	}
}
