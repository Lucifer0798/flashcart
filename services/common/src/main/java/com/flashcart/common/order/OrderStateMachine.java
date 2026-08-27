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
 */
public final class OrderStateMachine {

	private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

	static {
		ALLOWED.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.RESERVED, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.RESERVED,
				EnumSet.of(OrderStatus.PAYMENT_PENDING, OrderStatus.RESERVATION_EXPIRED, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.PAYMENT_PENDING,
				EnumSet.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.PAYMENT_TIMEOUT));
		ALLOWED.put(OrderStatus.PAID, EnumSet.of(OrderStatus.FULFILLING, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.FULFILLING, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
		ALLOWED.put(OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED));
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
