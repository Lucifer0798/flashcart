package com.flashcart.common.error;

/**
 * Base for errors that carry a stable machine-readable code across the service boundary.
 *
 * <p>The code, not the HTTP status and not the message, is what callers branch on — messages are
 * for humans and statuses are too coarse to distinguish "sold out" from "reservation expired".
 */
public abstract class FlashCartException extends RuntimeException {

	private final String code;

	protected FlashCartException(String code, String message) {
		super(message);
		this.code = code;
	}

	protected FlashCartException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
