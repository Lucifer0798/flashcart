package com.flashcart.order.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How the order service asks inventory to hold, keep or return stock.
 *
 * <p>An interface rather than a class because this is the seam Phase 5 replaces. Today it is a
 * synchronous HTTP call; when the event bus arrives, {@code reserve} becomes a published command and
 * the reply arrives as an event, and nothing in {@link com.flashcart.order.service.OrderService}
 * should need to change to accommodate that.
 *
 * <p>Every method is safe to retry. The reservation key is the order id, and inventory is idempotent
 * on it, which is what makes a timed-out call something to repeat rather than something to guess
 * about.
 */
public interface InventoryClient {

	/**
	 * Hold stock for an order, all lines or none.
	 *
	 * @throws InventoryRejectedException when inventory refuses — out of stock, allocation gone, or
	 *         the customer's cap reached. An expected outcome during a sale, not a fault.
	 * @throws InventoryUnavailableException when inventory could not be reached or did not answer in
	 *         time. Crucially different: the hold may or may not exist, so the caller must retry
	 *         rather than assume either way.
	 */
	Reservation reserve(ReserveCommand command);

	/** Turn a hold into a sale. Idempotent. */
	void commit(String reservationKey);

	/** Give a hold back. Idempotent, and a no-op on a hold that already lapsed. */
	void release(String reservationKey, String reason);

	/**
	 * @param reservationKey the order id, reused so inventory's idempotency comes for free
	 * @param flashSaleId    non-null to have the sale allocation and per-customer cap enforced
	 */
	record ReserveCommand(
			String reservationKey,
			String customerId,
			UUID flashSaleId,
			List<Line> lines) {

		public record Line(String sku, int quantity) {
		}
	}

	/**
	 * @param expiresAt when the hold lapses — the order service mirrors this so its reconciler can
	 *                  find lapsed orders without waiting to be told
	 */
	record Reservation(String reservationKey, String status, Instant expiresAt) {
	}
}
