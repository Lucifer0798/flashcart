package com.flashcart.order.client;

import com.flashcart.common.error.FlashCartException;

/**
 * Inventory could not be reached, or did not answer in time.
 *
 * <p>The genuinely hard case, and the reason it is a separate type: the hold may exist or may not,
 * and the order service cannot tell. It must not cancel the order (that could strand a real hold
 * until it expires) and must not confirm it (there may be nothing held at all). Retrying is safe
 * only because the reservation key makes inventory idempotent.
 *
 * <p>This is the same shape of problem as {@code PAYMENT_TIMEOUT} in the order state machine, and
 * for the same reason: silence is not a "no".
 */
public class InventoryUnavailableException extends FlashCartException {

	public InventoryUnavailableException(String message, Throwable cause) {
		super("INVENTORY_UNAVAILABLE", message, cause);
	}
}
