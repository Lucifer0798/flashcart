package com.flashcart.common.order;

import com.flashcart.common.error.FlashCartException;

/** Raised when something tries to move an order along an edge the state machine does not have. */
public class IllegalOrderTransitionException extends FlashCartException {

	private final OrderStatus from;
	private final OrderStatus to;

	public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
		super("ORDER_ILLEGAL_TRANSITION", "Order cannot move from %s to %s".formatted(from, to));
		this.from = from;
		this.to = to;
	}

	public OrderStatus getFrom() {
		return from;
	}

	public OrderStatus getTo() {
		return to;
	}
}
