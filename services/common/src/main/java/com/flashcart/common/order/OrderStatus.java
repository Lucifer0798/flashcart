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

	/** Terminal. Stock is back on the shelf and any capture has been refunded. */
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
