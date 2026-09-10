package com.flashcart.gateway.web;

import java.util.List;

import com.flashcart.common.security.AccessTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Turns unauthenticated traffic away at the edge.
 *
 * <p>This filter is <strong>not</strong> the platform's security boundary, and it is important not to
 * mistake it for one. Compose publishes every service on its own host port, so anything that can
 * reach this gateway can reach the order service directly and skip this check entirely. What the
 * filter buys is the thing this platform cares about everywhere else: refusing doomed work before it
 * costs a connection, a thread, or a database round trip.
 *
 * <p>The service that must not be wrong about who is buying verifies the token itself. See
 * {@code OrderController} and ADR 0021.
 *
 * <p>Browsing is public on purpose. A shopper looking at a flash sale has not signed in yet, and
 * requiring a token to read a product page would make the catalog useless for the thing it exists to
 * do.
 */
@Component
public class AuthenticationWebFilter implements WebFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(AuthenticationWebFilter.class);

	/**
	 * What does not require a shopper's token.
	 *
	 * <p>Two different things are on this list and it is worth not confusing them.
	 *
	 * <p>The first three are <strong>public because browsing is public</strong>: a shopper looking at
	 * a flash sale has not signed in yet, and requiring a token to read a product page would make the
	 * catalog useless for the thing it exists to do. Registration and sign-in obviously cannot require
	 * a token either.
	 *
	 * <p>The last is <strong>stock availability</strong>, which stays public because a shopper needs
	 * to know how many are left and a count carries nobody personal data. The rest of inventory, and
	 * all of payment and shipping, required an operator as of ADR 0022 — and each of those services
	 * enforces that itself, because this filter is an optimisation rather than a boundary.
	 */
	private static final List<String> PUBLIC_PREFIXES = List.of(
			"/api/v1/products",
			"/api/v1/categories",
			"/api/v1/flash-sales",
			// Registration and sign-in, which obviously cannot require a token.
			"/api/v1/users",
			"/actuator",

			// Stock availability only. The rest of inventory, and all of payment and shipping, now
			// require an operator and are checked by those services themselves -- the gateway simply
			// stops carrying unauthenticated traffic to them. See ADR 0022.
			"/api/v1/inventory/stock/");

	/** The `_info` endpoints and Swagger UI stay reachable; they are how the stack is inspected. */
	private static final List<String> PUBLIC_SUFFIXES = List.of("/_info", "/swagger-ui.html", "/v3/api-docs");

	private final AccessTokens tokens;

	public AuthenticationWebFilter(AccessTokens tokens) {
		this.tokens = tokens;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		String path = request.getPath().value();

		if (request.getMethod() == HttpMethod.OPTIONS || isPublic(path)) {
			return chain.filter(exchange);
		}

		String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (AccessTokens.bearer(authorization).flatMap(tokens::subject).isPresent()) {
			return chain.filter(exchange);
		}

		log.debug("Refused an unauthenticated {} {}", request.getMethod(), path);
		return unauthorised(exchange);
	}

	private boolean isPublic(String path) {
		return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)
				|| PUBLIC_SUFFIXES.stream().anyMatch(path::endsWith);
	}

	/**
	 * The platform's error envelope, written by hand.
	 *
	 * <p>The gateway is reactive and does not share the servlet exception handler that produces this
	 * shape everywhere else, so a client must not be able to tell from the response body that it was
	 * refused at the edge rather than by a service.
	 */
	private Mono<Void> unauthorised(ServerWebExchange exchange) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(HttpStatus.UNAUTHORIZED);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

		String correlationId = exchange.getRequest().getHeaders()
				.getFirst(com.flashcart.common.web.CorrelationId.HEADER);
		String body = """
				{"timestamp":"%s","status":401,"code":"UNAUTHENTICATED",\
				"message":"A valid access token is required","path":"%s","correlationId":%s}"""
				.formatted(java.time.Instant.now(), exchange.getRequest().getPath().value(),
						correlationId == null ? "null" : "\"" + correlationId + "\"");

		DataBuffer buffer = response.bufferFactory().wrap(body.getBytes());
		return response.writeWith(Mono.just(buffer));
	}

	/**
	 * After the correlation id filter, so a refusal still carries one and can be found in the logs.
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 10;
	}
}
