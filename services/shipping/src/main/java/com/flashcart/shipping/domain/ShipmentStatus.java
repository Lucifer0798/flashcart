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
	CANCELLED
}
