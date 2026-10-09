package com.flashcart.user.notice;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * What each email says. See ADR 0048.
 *
 * <p>Plain text, written for the person reading it rather than for the platform: no status codes, no
 * internal names, and nothing promised that the platform cannot keep.
 */
public enum NoticeKind {

	BACK_IN_STOCK {
		@Override
		Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore) {
			String sku = (String) payload.get("sku");
			Object heldUntil = payload.get("heldUntil");
			String held = heldUntil == null
					? "It may not last, so if you still want it, now is the time."
					: ("We are holding one for you until %s. Check out before then and it is yours; after that it "
							+ "goes to the next person waiting.").formatted(TIME.format(Instant.parse((String) heldUntil)));
			return new Email("Back in stock: " + sku,
					"%s is back in stock, and you were next in line.\n\n%s".formatted(sku, held), name);
		}
	},

	ORDER_CONFIRMED {
		@Override
		Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore) {
			return new Email("Order %s confirmed".formatted(orderNumber),
					("Your payment went through and order %s is confirmed. We will email you again when it "
							+ "leaves the warehouse.").formatted(orderNumber), name);
		}
	},

	ORDER_DISPATCHED {
		@Override
		Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore) {
			return new Email("Order %s is on its way".formatted(orderNumber),
					("Order %s has left the warehouse with the carrier. It can no longer be cancelled; if it "
							+ "is not what you wanted, you can return it once it arrives.").formatted(orderNumber), name);
		}
	},

	ORDER_DELIVERED {
		@Override
		Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore) {
			return new Email("Order %s was delivered".formatted(orderNumber),
					"Order %s has been delivered. We hope you enjoy it.".formatted(orderNumber), name);
		}
	},

	ORDER_CANCELLED {
		@Override
		Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore) {
			return new Email("Order %s was cancelled".formatted(orderNumber),
					"Order %s was cancelled. %s".formatted(orderNumber, why((String) payload.get("reason"))), name);
		}

		/**
		 * The order service's reason code, said the way a customer would want to hear it. Codes it does not
		 * know get a plain sentence rather than the code: the email is for the shopper, not the log.
		 */
		private String why(String reason) {
			// Exact codes, not substrings. Matching "LIMIT" as "sold out" told a shopper who had reached
			// the per-customer cap that the item was gone, which is a different and worse thing to hear.
			// Every code but the first is a cancellation before money moved; the order service raises
			// CANCELLED_AFTER_PAYMENT and nothing else after it.
			return switch (reason == null ? "" : reason) {
				case "CANCELLED_AFTER_PAYMENT" -> "You were charged, so the money is on its way back -- we will "
						+ "email you when the refund has gone through.";
				case "INSUFFICIENT_STOCK", "SALE_ALLOCATION_EXHAUSTED" ->
						"It sold out before we could hold one for you. You have not been charged.";
				case "CUSTOMER_LIMIT_EXCEEDED" ->
						"This sale allows a limited number per customer, and you have reached it. You have not been charged.";
				case "NO_SALE_ALLOCATION" -> "That item is not part of the sale. You have not been charged.";
				case "CARD_DECLINED" -> "Your payment was declined, so nothing was charged.";
				case "RESERVATION_EXPIRED" ->
						"Payment did not complete in time, so the item was released. You have not been charged.";
				default -> "You have not been charged.";
			};
		}
	},

	REFUNDED {
		@Override
		Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore) {
			return new Email("Refund for order %s".formatted(orderNumber),
					("We have refunded %s %s for order %s. Depending on your bank it can take a few days to "
							+ "appear.").formatted(payload.get("amount"), payload.get("currency"), orderNumber), name);
		}
	};

	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	/** A rendered email, before it has a recipient. */
	public record Email(String subject, String body, String name) {

		public String text() {
			return "Hi %s,\n\n%s\n\n-- FlashCart\n".formatted(name, body);
		}
	}

	abstract Email render(String name, String orderNumber, Map<String, Object> payload, Instant sendBefore);
}
