package com.flashcart.common.error;

/**
 * The request is well formed but collides with current state — a duplicate SKU, an order that has
 * already moved on, stock that was taken by someone else. Maps to 409.
 */
public class ConflictException extends FlashCartException {

	public ConflictException(String message) {
		super("CONFLICT", message);
	}

	public ConflictException(String code, String message) {
		super(code, message);
	}
}
