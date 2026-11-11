package com.flashcart.gateway.web;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import com.flashcart.common.api.ApiError;
import com.flashcart.common.web.CorrelationId;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

/**
 * A service that could not be reached, or that dropped the connection, is a 503 and not a 500. See ADR 0071.
 *
 * <p>The services already shed load as 503 with {@code Retry-After} (ADR 0010, and the handler in common).
 * The gateway undid that whenever the overload showed as a network failure instead of an answer: at 2,000
 * simultaneous sign-ins the user service reset 26 connections, and each reached the shopper as a 500,
 * "Internal Server Error", which says the platform is broken and invites nobody to try again.
 *
 * <p>Only failures to talk to the upstream are handled here. Everything else, including a 500 the upstream
 * really answered, goes on to the default handler unchanged.
 *
 * <p>A reset can come after the request was sent, so the upstream may have done the work. 503 is still the
 * honest answer about the gateway's position: it does not know, and trying again is how a client finds out.
 * Every write a shopper would retry here takes an idempotency key, or, like sign-in, is safe to repeat.
 */
@Component
public class UpstreamFailureHandler implements WebExceptionHandler, Ordered {

	private static final Logger log = LoggerFactory.getLogger(UpstreamFailureHandler.class);

	private final JsonMapper json;

	public UpstreamFailureHandler(JsonMapper json) {
		this.json = json;
	}

	@Override
	public int getOrder() {
		// Before Boot's DefaultErrorWebExceptionHandler, which is -1 and would turn these into 500.
		return -2;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (!isUpstreamFailure(ex) || exchange.getResponse().isCommitted()) {
			return Mono.error(ex);
		}
		String path = exchange.getRequest().getPath().value();
		log.warn("Upstream unavailable for {} {}: {}", exchange.getRequest().getMethod(), path, ex.toString());

		String correlationId = exchange.getResponse().getHeaders().getFirst(CorrelationId.HEADER);
		ApiError error = ApiError.of(HttpStatus.SERVICE_UNAVAILABLE.value(), "UPSTREAM_UNAVAILABLE",
				"The service behind this request is busy or unreachable; retry shortly", path, correlationId);
		byte[] body = json.writeValueAsString(error).getBytes(StandardCharsets.UTF_8);

		exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
		DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
		return exchange.getResponse().writeWith(Mono.just(buffer));
	}

	/** A connection refused, timed out, reset or closed early, anywhere in the cause chain. */
	static boolean isUpstreamFailure(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof PrematureCloseException || cause instanceof ConnectException
					|| cause instanceof ConnectTimeoutException) {
				return true;
			}
			// Netty's native transport reports a reset as an IOException subclass of its own; any I/O failure
			// on the way to the upstream is the same answer.
			if (cause instanceof IOException) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}
}
