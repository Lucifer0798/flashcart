package com.flashcart.shipping.domain;

/** Where a shipment is. */
public enum ShipmentStatus {

	/** Booked, not yet handed over. */
	CREATED,

	/** With the carrier. */
	DISPATCHED,

	/** Terminal, happy path. */
	DELIVERED,

	/** Pulled before dispatch. */
	/**
	 * Stopped before it left the warehouse.
	 *
	 * <p>Reachable only from {@code CREATED}. The schema has listed this value since the first
	 * migration and nothing could produce it until ADR 0030; a customer cancelling a paid order is what
	 * it was always for.
	 */
	CANCELLED
}
