package com.flashcart.common.event;

/**
 * Kafka topic names, in one place so a producer and its consumers cannot drift apart.
 *
 * <p>The split between <strong>commands</strong> and <strong>events</strong> is the important part,
 * and it follows from the saga being orchestrated rather than choreographed:
 *
 * <ul>
 *   <li>A <em>command</em> topic carries an instruction to one service — "reserve this stock". It
 *       has exactly one consumer, and the sender expects something to happen.</li>
 *   <li>An <em>event</em> topic carries a statement of fact — "this stock was reserved". Anyone may
 *       subscribe, and the publisher neither knows nor cares who does.</li>
 * </ul>
 *
 * <p>Mixing the two on one topic is how event-driven systems become unreadable: it stops being
 * possible to tell, from a topic name, whether a message is someone's instruction or someone's news.
 *
 * <p>Every message is keyed by its aggregate id — the order id, almost always. Kafka orders messages
 * within a partition only, so keying by order is what guarantees one order's events arrive in the
 * sequence they happened.
 */
public final class Topics {

	/** Order lifecycle facts: created, confirmed, cancelled. Keyed by order id. */
	public static final String ORDER_EVENTS = "flashcart.order.events";

	/** Instructions to inventory: reserve, release, commit. Keyed by order id. */
	public static final String INVENTORY_COMMANDS = "flashcart.inventory.commands";

	/** What inventory did: reserved, refused, released, committed, expired. Keyed by order id. */
	public static final String INVENTORY_EVENTS = "flashcart.inventory.events";

	/** Instructions to payment: take this money. Keyed by order id. */
	public static final String PAYMENT_COMMANDS = "flashcart.payment.commands";

	/** What payment did: completed, failed, timed out. Keyed by order id. */
	public static final String PAYMENT_EVENTS = "flashcart.payment.events";

	/** Instructions to shipping: create this shipment. Keyed by order id. */
	public static final String SHIPPING_COMMANDS = "flashcart.shipping.commands";

	/** What shipping did: shipment created, dispatched, delivered. Keyed by order id. */
	public static final String SHIPPING_EVENTS = "flashcart.shipping.events";

	/** Catalog changes, so read models and caches can invalidate. Keyed by product id. */
	public static final String CATALOG_EVENTS = "flashcart.catalog.events";

	/**
	 * Where a message goes once a consumer has exhausted its retries.
	 *
	 * <p>One topic rather than one per source, so failure handling has a single place to look — and
	 * so nobody has to remember to create a new dead-letter topic when they add a consumer. The
	 * original topic, partition and offset travel in headers.
	 */
	public static final String DEAD_LETTER = "flashcart.dlq";

	private Topics() {
	}
}
