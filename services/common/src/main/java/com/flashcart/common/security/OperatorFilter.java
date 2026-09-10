package com.flashcart.common.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import com.flashcart.common.web.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires an operator's token for everything a service does not explicitly declare public.
 *
 * <p><strong>Default-deny, and that direction is the entire point.</strong> The gateway's public list
 * works the other way round — it names what is open and lets everything else through to be checked
 * later — which is right for an edge that mostly routes. It is wrong here. A service that lists what
 * to protect forgets a new endpoint and ships it open; a service that lists what to expose forgets a
 * new endpoint and ships it closed. Only one of those failure modes is safe, and both are equally
 * likely.
 *
 * <p>This is one filter in {@code flashcart-common} rather than three implementations, because three
 * implementations is three chances to get "default-deny" subtly wrong, and the wrong one would look
 * exactly like the right one until somebody probed it.
 *
 * <h2>Why the operational APIs need this at all</h2>
 *
 * <p>Until now inventory, payment and shipping had no authentication of any kind. Nothing reached them
 * from outside <em>in this deployment</em> — but that was a claim about a compose file rather than a
 * property of the system, and compose publishes every one of them on its own host port. Anybody who
 * could reach the gateway could receive five thousand units of stock, adjust a ledger, or mark a
 * parcel delivered, without an account.
 *
 * <p>A shopper's token is not enough. Being signed in makes somebody a customer, not a warehouse.
 */
public class OperatorFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(OperatorFilter.class);

	private final AccessTokens tokens;
	private final List<String> publicPaths;

	/**
	 * @param publicPaths what needs no token at all. Each entry is optionally prefixed with an HTTP
	 *                    method ({@code "GET /api/v1/x/*"}), and ends either literally, with
	 *                    {@code *} for exactly one more path segment, or {@code **} for any number.
	 *                    Everything else on this service requires {@link AccessTokens#OPERATOR}.
	 *                    <p>The single-star form is the one that matters: {@code /stock/*} exposes
	 *                    availability for one SKU without also exposing {@code /stock/{sku}/movements}
	 *                    or {@code /stock/{sku}/receive}, which a {@code **} would have done silently.
	 */
	public OperatorFilter(AccessTokens tokens, List<String> publicPaths) {
		this.tokens = tokens;
		this.publicPaths = List.copyOf(publicPaths);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		String path = request.getRequestURI();
		if (HttpMethod.OPTIONS.matches(request.getMethod()) || isPublic(request.getMethod(), path)) {
			chain.doFilter(request, response);
			return;
		}

		String token = AccessTokens.bearer(request.getHeader(HttpHeaders.AUTHORIZATION)).orElse(null);
		if (tokens.isOperator(token)) {
			chain.doFilter(request, response);
			return;
		}

		// 403 when a valid token simply lacks the role, 401 when there is no usable token at all.
		// This is the opposite of the order service's deliberate 404 for somebody else's order: there,
		// distinguishing "not yours" from "does not exist" hands an attacker an oracle over guessable
		// order numbers. Here the paths are fixed and published in the OpenAPI document, so there is
		// nothing to conceal and a signed-in operator debugging a permissions problem deserves to be
		// told which of the two things is wrong.
		boolean authenticated = token != null && tokens.subject(token).isPresent();
		HttpStatus status = authenticated ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
		log.info("Refused {} {} with {}", request.getMethod(), path, status.value());
		write(request, response, status);
	}

	private boolean isPublic(String method, String path) {
		return publicPaths.stream().anyMatch(rule -> matches(rule, method, path));
	}

	// Package-private so the rule syntax can be tested directly. It is small, it is subtle, and
	// getting it wrong in either direction is silent: too narrow refuses a public page, too wide
	// exposes the ledger sitting one segment below it.
	static boolean matches(String rule, String method, String path) {
		int space = rule.indexOf(' ');
		if (space > 0) {
			if (!rule.substring(0, space).equalsIgnoreCase(method)) {
				return false;
			}
			rule = rule.substring(space + 1);
		}
		if (rule.endsWith("/**")) {
			return path.startsWith(rule.substring(0, rule.length() - 2));
		}
		if (rule.endsWith("/*")) {
			String prefix = rule.substring(0, rule.length() - 1);
			// Exactly one more segment: a trailing slash in what remains means the caller reached
			// further down the tree than this rule allows.
			return path.startsWith(prefix)
					&& !path.substring(prefix.length()).isEmpty()
					&& !path.substring(prefix.length()).contains("/");
		}
		if (rule.endsWith("**")) {
			return path.startsWith(rule.substring(0, rule.length() - 2));
		}
		return path.equals(rule);
	}

	/** The platform's error envelope, so a refusal here looks like a refusal anywhere else. */
	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		String correlationId = CorrelationId.current();
		response.getWriter().write("""
				{"timestamp":"%s","status":%d,"code":"%s","message":"%s","path":"%s","correlationId":%s}"""
				.formatted(Instant.now(), status.value(),
						status == HttpStatus.FORBIDDEN ? "OPERATOR_REQUIRED" : "UNAUTHENTICATED",
						status == HttpStatus.FORBIDDEN
								? "This operation requires an operator account"
								: "A valid access token is required",
						request.getRequestURI(),
						correlationId == null ? "null" : "\"" + correlationId + "\""));
	}
}
