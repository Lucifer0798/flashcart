package com.flashcart.common.error;

/**
 * The caller is not who it says it is, or did not say.
 *
 * <p>Maps to <strong>401</strong>. Lives in {@code flashcart-common} rather than in the user service
 * for the same reason {@link UpstreamUnavailableException} does: the handler that turns exceptions
 * into responses is shared, and a handler cannot map a type it has never heard of. The user service
 * learned that the hard way when a silent catalog produced a 500.
 *
 * <p>One code and one message covers a wrong password, an unknown email and a disabled account,
 * deliberately. A client that could tell them apart could enumerate accounts, and "no account exists
 * for that email" is a more useful sentence to someone building a list than to someone who mistyped
 * their own address.
 */
public class UnauthenticatedException extends FlashCartException {

	public UnauthenticatedException() {
		super("INVALID_CREDENTIALS", "Email or password is incorrect");
	}

	public UnauthenticatedException(String message) {
		super("UNAUTHENTICATED", message);
	}
}
