package com.flashcart.gateway.web;

import java.util.UUID;

import com.flashcart.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Stamps every inbound request with a correlation id before it is proxied downstream.
 *
 * <p>This is the reactive twin of {@code CorrelationIdFilter} in flashcart-common, which is
 * servlet-only and therefore does not apply on the gateway's WebFlux stack. Doing it here rather
 * than leaving each service to mint its own id is the whole point: one checkout fans out to
 * inventory, payment and shipping, and only a value assigned at the edge ties those logs together.
 *
 * <p>Note there is no MDC write here. In a reactive pipeline the work does not stay on one thread,
 * so a thread-local would be wrong more often than right; the gateway's own access log carries the
 * id via the exchange instead, and downstream servlet services populate their MDC from the header.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String incoming = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
		String correlationId = StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();

		// Mutate the proxied request so every downstream service sees the same id...
		ServerHttpRequest mutated = exchange.getRequest().mutate()
				.header(CorrelationId.HEADER, correlationId)
				.build();
		// ...and echo it to the caller so a user can quote it in a bug report.
		exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);

		return chain.filter(exchange.mutate().request(mutated).build());
	}
}
