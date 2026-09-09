package com.flashcart.common.security;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issues and verifies the platform's access tokens.
 *
 * <p>Lives in {@code flashcart-common}, with no Spring web dependency at all, because both sides need
 * it and they are not the same kind of application: the gateway is reactive and the order service is
 * servlet-based.
 *
 * <h2>Why the order service verifies as well as the gateway</h2>
 *
 * <p>The obvious design is for the gateway to check the token and inject a trusted
 * {@code X-Customer-Id} header, leaving downstream services to believe it. That is a perfectly good
 * design when the only route to a service is through the edge.
 *
 * <p>It is not this deployment. Compose publishes every service on its own host port — the README
 * tells you to open catalog's Swagger UI on 18081 — so anything that can reach the gateway can reach
 * the order service directly on 18082 and simply send its own header. A gateway-only check would be
 * an authentication system that any client could opt out of by changing a port number.
 *
 * <p>So the gateway rejects unauthenticated requests <em>early</em>, which is worth doing on a
 * platform built around shedding doomed work before it costs anything, and the order service
 * independently verifies the same token because it is the one that must not be wrong about who is
 * buying.
 *
 * <h2>HMAC, and what that costs</h2>
 *
 * <p>A shared secret rather than a public/private pair, which means every verifier can also mint.
 * Acceptable here because all of them are this platform, and honest to record because it stops being
 * acceptable the moment a third party needs to verify a token: that is the day this becomes RS256 and
 * the user service keeps the private half.
 */
public final class AccessTokens {

	private static final Logger log = LoggerFactory.getLogger(AccessTokens.class);

	/** Who the token is about: the user id, which is the {@code customerId} everything else uses. */
	public static final String SUBJECT = "sub";

	private static final String ISSUER = "flashcart-user";
	private static final String EMAIL_CLAIM = "email";

	private final byte[] secret;
	private final Duration ttl;

	public AccessTokens(String secret, Duration ttl) {
		// HS256 requires at least 256 bits. Failing loudly at startup beats discovering it on the
		// first login attempt, and a short secret is exactly the sort of thing a .env file grows.
		if (secret == null || secret.getBytes().length < 32) {
			throw new IllegalArgumentException(
					"flashcart.security.jwt.secret must be at least 32 bytes for HS256");
		}
		this.secret = secret.getBytes();
		this.ttl = ttl;
	}

	/** Mints a token for a user. Only the user service should be calling this. */
	public String issue(String userId, String email) {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId)
				.issuer(ISSUER)
				.claim(EMAIL_CLAIM, email)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(ttl)))
				.build();

		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		try {
			jwt.sign(new MACSigner(secret));
		}
		catch (JOSEException ex) {
			// Signing cannot fail for reasons a caller could act on: the key is fixed at startup and
			// already length-checked. If it does, the service is misconfigured, not the request.
			throw new IllegalStateException("Could not sign an access token", ex);
		}
		return jwt.serialize();
	}

	/**
	 * The subject of a valid token, or empty for anything else.
	 *
	 * <p>Deliberately returns {@link Optional} rather than throwing, and deliberately does not
	 * distinguish "expired" from "forged" from "malformed" in its return value. A caller that could
	 * tell those apart would be tempted to tell a client too, and "your signature is wrong" is a more
	 * useful sentence to an attacker than to a shopper. The distinction is logged, not returned.
	 */
	public Optional<String> subject(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!jwt.verify(new MACVerifier(secret))) {
				log.debug("Rejected a token with a bad signature");
				return Optional.empty();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Date expiry = claims.getExpirationTime();
			if (expiry == null || expiry.toInstant().isBefore(Instant.now())) {
				log.debug("Rejected an expired token for {}", claims.getSubject());
				return Optional.empty();
			}
			if (!ISSUER.equals(claims.getIssuer())) {
				log.debug("Rejected a token from issuer {}", claims.getIssuer());
				return Optional.empty();
			}
			return Optional.ofNullable(claims.getSubject());
		}
		catch (ParseException | JOSEException ex) {
			log.debug("Rejected an unparseable token");
			return Optional.empty();
		}
	}

	/** Pulls the bearer value out of an {@code Authorization} header, if it looks like one. */
	public static Optional<String> bearer(String authorizationHeader) {
		if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return Optional.empty();
		}
		String token = authorizationHeader.substring(7).trim();
		return token.isEmpty() ? Optional.empty() : Optional.of(token);
	}
}
