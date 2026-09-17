package com.flashcart.common.security;

import com.flashcart.common.error.UnauthenticatedException;

/**
 * Who is asking, according to a token this service verified itself.
 *
 * <p>Three services had written this out identically — {@code OrderController},
 * {@code PaymentController} and {@code ShipmentController} each carried a byte-for-byte copy of
 * {@code caller()}, and two of them of {@code isOperator()}. ADR 0022 made the argument for putting
 * {@link OperatorFilter} in {@code flashcart-common} rather than writing it three times, and it
 * applies here word for word: three implementations is three chances to get it subtly wrong, and the
 * wrong one looks exactly like the right one until somebody probes it.
 *
 * <p>Deliberately separate from {@link CustomerDataAccess}. This type answers only "who is asking"
 * and "do they hold the role" — it grants nothing and records nothing. The order service uses this
 * and nothing else, because an operator has no business reading somebody else's order (ADR 0025), and
 * the split is what keeps that true: reading another customer's row lives on a type that cannot be
 * built without an audit table to write to.
 *
 * <h2>Why each service checks rather than trusting the edge</h2>
 *
 * <p>The gateway has already refused anything without a valid token, so in the normal path this
 * passes trivially. It is here because compose publishes every service on its own port, so a client
 * can skip the edge entirely — and a check performed only somewhere else is a check that disappears
 * the day the somewhere else is reconfigured. See ADR 0021.
 */
public class CallerIdentity {

	private final AccessTokens tokens;

	public CallerIdentity(AccessTokens tokens) {
		this.tokens = tokens;
	}

	/**
	 * The subject of a verified token.
	 *
	 * @throws UnauthenticatedException when the header is absent, malformed, expired or forged —
	 *         deliberately not distinguished, for the reason {@link AccessTokens#subject} gives.
	 */
	public String require(String authorization) {
		return AccessTokens.bearer(authorization)
				.flatMap(tokens::subject)
				.orElseThrow(() -> new UnauthenticatedException("A valid access token is required"));
	}

	/** Whether the caller holds {@link AccessTokens#OPERATOR}. False for anything unreadable. */
	public boolean isOperator(String authorization) {
		return AccessTokens.bearer(authorization).filter(tokens::isOperator).isPresent();
	}
}
