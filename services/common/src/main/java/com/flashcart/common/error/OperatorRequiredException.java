package com.flashcart.common.error;

import com.flashcart.common.security.AccessTokens;

/**
 * A valid token, signed in as somebody real, asking for something only an operator may have.
 *
 * <p>Maps to <strong>403</strong>, and is deliberately distinct from
 * {@link UnauthenticatedException}'s 401: there is nothing wrong with the caller's credentials, so
 * telling them to authenticate again would send them round a loop that cannot succeed.
 *
 * <p>Lives in {@code flashcart-common} beside the handler that maps it, for the reason
 * {@link UnauthenticatedException} gives — a shared handler cannot map a type it has never heard of.
 *
 * <p>Note where this is <em>not</em> used. Reading another customer's payment or shipment by its
 * identifier answers 404, not this, because a 403 there confirms the identifier exists and hands an
 * enumerator an oracle. This is for the case where the request names whose data it wants outright:
 * nothing is revealed by refusing it plainly, and a support engineer who genuinely lacks the role
 * deserves to be told which of the two problems they have. Same distinction
 * {@link com.flashcart.common.security.OperatorFilter} draws, and the same reasoning as
 * {@link AccessTokens#OPERATOR}.
 */
public class OperatorRequiredException extends FlashCartException {

	public OperatorRequiredException() {
		this("This operation requires an operator account");
	}

	public OperatorRequiredException(String message) {
		super("OPERATOR_REQUIRED", message);
	}
}
