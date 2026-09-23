package com.flashcart.common.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for legal order transitions.
 *
 * <p>The order service persists {@code OrderStatus} and consults this table before every write, so
 * a duplicate or out-of-order event (a payment callback arriving twice, a reservation-expiry timer
 * firing after the charge already settled) is rejected by the state machine instead of corrupting
 * the order. Keeping the table here — not inside a service — means the same rules can be asserted
 * from a consumer test in any module.
 *
 * <p>Happy path:
 * <pre>
 * CREATED -&gt; RESERVED -&gt; PAYMENT_PENDING -&gt; PAID -&gt; FULFILLING -&gt; SHIPPED -&gt; DELIVERED
 * </pre>
 *
 * <p>Failure paths:
 * <pre>
 * PAYMENT_PENDING -&gt; PAYMENT_FAILED      -&gt; CANCELLED   (release inventory)
 * RESERVED        -&gt; RESERVATION_EXPIRED -&gt; CANCELLED   (release inventory)
 * PAYMENT_PENDING -&gt; PAYMENT_TIMEOUT     -&gt; PAID | CANCELLED (reconciliation decides)
 * </pre>
 *
 * <p>Cancelling after the money has moved:
 * <pre>
 * SHIPPED -&gt; CANCELLATION_REQUESTED -&gt; CANCELLED  (consignment stopped, capture refunded)
 *                                   -&gt; DISPATCHED (refused; the parcel had already left)
 * </pre>
 */
public final class OrderStateMachine {

	private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

	static {
		ALLOWED.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.RESERVED, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.RESERVED,
				EnumSet.of(OrderStatus.PAYMENT_PENDING, OrderStatus.RESERVATION_EXPIRED, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.PAYMENT_PENDING,
				EnumSet.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.PAYMENT_TIMEOUT));
		// No edge to CANCELLED from either of these, and that is the point. Both once had one, and
		// taking it compensated nothing: the capture stayed with us and the committed units did not
		// come back, so the customer lost the goods and the money. Cancelling a paid order is now a
		// request, and it is made from SHIPPED -- see below.
		ALLOWED.put(OrderStatus.PAID, EnumSet.of(OrderStatus.FULFILLING));
		ALLOWED.put(OrderStatus.FULFILLING, EnumSet.of(OrderStatus.SHIPPED));
		// SHIPPED means a consignment exists, not that it has left the building -- exactly the window
		// a customer wants to cancel in. DISPATCHED is the other side of that line, and having it
		// here is what lets the order service refuse a cancellation without asking.
		//
		// SHIPPED -> DELIVERED stays legal and is not dead weight. Dispatch and delivery are consumed
		// by separate groups (they must be: two listeners in one group split the partitions and drop
		// each other's messages), so nothing orders them relative to each other. If a delivery is
		// applied first, this edge is what stops it being declined and the arrival lost.
		ALLOWED.put(OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DISPATCHED, OrderStatus.DELIVERED,
				OrderStatus.CANCELLATION_REQUESTED));
		// No cancellation edge. The parcel is with the carrier, and this is the refusal that used to
		// cost a round trip to shipping and come back saying the same thing.
		ALLOWED.put(OrderStatus.DISPATCHED, EnumSet.of(OrderStatus.DELIVERED));
		// Shipping decides which of these it becomes, and it only ever refuses because the shipment
		// is DISPATCHED or DELIVERED -- so there is deliberately no edge back to SHIPPED. There was
		// one until DISPATCHED existed, and it had become unreachable the moment this state did.
		ALLOWED.put(OrderStatus.CANCELLATION_REQUESTED,
				EnumSet.of(OrderStatus.CANCELLED, OrderStatus.DISPATCHED, OrderStatus.DELIVERED));
		// Terminal. A delivered order is a return, which is a different transaction entirely.
		ALLOWED.put(OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class));
		// Compensation states funnel into CANCELLED once inventory is actually back.
		ALLOWED.put(OrderStatus.PAYMENT_FAILED, EnumSet.of(OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.RESERVATION_EXPIRED, EnumSet.of(OrderStatus.CANCELLED));
		// A timeout is genuinely undecided: reconciliation may find the charge did land.
		ALLOWED.put(OrderStatus.PAYMENT_TIMEOUT, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
	}

	private OrderStateMachine() {
	}

	/** The states reachable in one step from {@code from}. Empty for terminal states. */
	public static Set<OrderStatus> nextStates(OrderStatus from) {
		return Set.copyOf(ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)));
	}

	/** True when {@code from -> to} is a legal single transition. */
	public static boolean canTransition(OrderStatus from, OrderStatus to) {
		return ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to);
	}

	/**
	 * @throws IllegalOrderTransitionException when the move is not on the table above
	 */
	public static void assertTransition(OrderStatus from, OrderStatus to) {
		if (!canTransition(from, to)) {
			throw new IllegalOrderTransitionException(from, to);
		}
	}

	/**
	 * True when reaching {@code state} obliges us to hand held stock back to the catalog.
	 * Both compensation paths in the spec ({@code PAYMENT_FAILED} and {@code RESERVATION_EXPIRED})
	 * answer true; {@code PAYMENT_TIMEOUT} deliberately does not.
	 */
	public static boolean releasesInventory(OrderStatus state) {
		return state == OrderStatus.PAYMENT_FAILED || state == OrderStatus.RESERVATION_EXPIRED;
	}
}
