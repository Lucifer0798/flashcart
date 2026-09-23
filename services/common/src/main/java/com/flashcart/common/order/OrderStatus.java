package com.flashcart.common.order;

/**
 * Every state an order can occupy, happy path and failure paths alike.
 *
 * <p>Declared here rather than inside the order service because the state names travel on the
 * event bus: inventory, payment and shipping all react to transitions they do not own. The legal
 * moves between these states live in {@link OrderStateMachine}.
 */
public enum OrderStatus {

	/** Order row exists; nothing has been reserved or charged yet. */
	CREATED(false),

	/** Inventory service has held stock for every line. The hold has an expiry. */
	RESERVED(false),

	/** Payment has been requested and we are waiting on the provider. */
	PAYMENT_PENDING(false),

	/** Funds captured. The reservation is now permanent. */
	PAID(false),

	/** Warehouse is picking and packing. */
	FULFILLING(false),

	/** Handed to the carrier. */
	SHIPPED(false),

	/**
	 * The parcel has left the building.
	 *
	 * <p>The distinction {@code SHIPPED} was pretending to make. {@code SHIPPED} means a consignment
	 * record exists, which is the window a customer may still cancel in; this means it is with the
	 * carrier, which is the window they may not. Before this state the boundary lived only inside
	 * shipping, and the order had to ask over the bus to find out which side of it an order was on.
	 */
	DISPATCHED(false),

	/** Terminal, happy path. */
	DELIVERED(true),

	/** Provider declined the charge. Held inventory must be released. */
	PAYMENT_FAILED(false),

	/** The reservation timer ran out before payment completed. Held inventory must be released. */
	RESERVATION_EXPIRED(false),

	/**
	 * The provider neither confirmed nor declined in time. Unlike a decline this is <em>not</em>
	 * safe to auto-release: the charge may still land. A reconciliation job settles it.
	 */
	PAYMENT_TIMEOUT(false),

	/**
	 * The customer has asked for a paid order to be stopped, and shipping has not answered yet.
	 *
	 * <p>Not terminal, and deliberately not {@code CANCELLED}: whether this order can be cancelled at
	 * all is shipping's to answer, because only shipping knows whether the parcel has left. It resolves
	 * to {@code CANCELLED} if the consignment was still in the warehouse, or to {@code DISPATCHED} (or
	 * {@code DELIVERED}) if it was not.
	 */
	CANCELLATION_REQUESTED(false),

	/**
	 * Terminal. Nothing is owed in either direction.
	 *
	 * <p>What that means depends on how the order got here. Before payment, the hold has been given
	 * back to inventory. After payment, the consignment was cancelled before dispatch and the capture
	 * has been refunded. The units themselves are <em>not</em> returned to stock; ADR 0030 says why.
	 */
	CANCELLED(true);

	private final boolean terminal;

	OrderStatus(boolean terminal) {
		this.terminal = terminal;
	}

	/** True when no further transition out of this state is legal. */
	public boolean isTerminal() {
		return terminal;
	}
}
