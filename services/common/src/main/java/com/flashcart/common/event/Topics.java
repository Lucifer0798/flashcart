package com.flashcart.common.event;

/**
 * Kafka topic names, in one place so a producer and its consumers cannot drift apart.
 *
 * <p>Naming is {@code flashcart.<aggregate>.<event-family>}. Topics are per-aggregate rather than
 * per-consumer so a new service can subscribe without the producer changing, and every message on a
 * topic keys on that aggregate's id — which is what gives per-order ordering under partitioning.
 *
 * <p>Declared in Phase 1 as part of the architecture; produced and consumed from Phase 5 onward.
 */
public final class Topics {

	/** Order lifecycle: created, confirmed, cancelled. Keyed by order id. */
	public static final String ORDER_EVENTS = "flashcart.order.events";

	/** Reservation outcomes: reserved, release requested, released, expired. Keyed by order id. */
	public static final String INVENTORY_EVENTS = "flashcart.inventory.events";

	/** Payment lifecycle: requested, completed, failed, timed out. Keyed by order id. */
	public static final String PAYMENT_EVENTS = "flashcart.payment.events";

	/** Shipment lifecycle: created, dispatched, delivered. Keyed by order id. */
	public static final String SHIPPING_EVENTS = "flashcart.shipping.events";

	/** Catalog changes, so read models and caches can invalidate. Keyed by product id. */
	public static final String CATALOG_EVENTS = "flashcart.catalog.events";

	/**
	 * Where a message goes after a consumer has exhausted its retries. Kept as one topic rather than
	 * one per source so failure handling has a single place to look.
	 */
	public static final String DEAD_LETTER = "flashcart.dlq";

	private Topics() {
	}
}
