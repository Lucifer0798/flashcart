package com.flashcart.common.error;

/**
 * Refused because too many attempts have failed recently. Answered 429 with {@code Retry-After}.
 *
 * <p>Its own type rather than a code on another, because it carries something the others do not: when
 * trying again is worth it. See ADR 0058.
 */
public class TooManyAttemptsException extends FlashCartException {

	private final long retryAfterSeconds;

	public TooManyAttemptsException(String message, long retryAfterSeconds) {
		super("TOO_MANY_ATTEMPTS", message);
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
