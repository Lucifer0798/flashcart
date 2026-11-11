package com.flashcart.gateway.web;

import java.io.IOException;
import java.net.ConnectException;

import com.flashcart.common.web.CorrelationId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An upstream that cannot be reached is a 503 the client may retry, not a 500. See ADR 0071. */
class UpstreamFailureHandlerTest {

	private final UpstreamFailureHandler handler = new UpstreamFailureHandler(JsonMapper.builder().build());

	@Test
	@DisplayName("a connection reset by the upstream becomes 503 with Retry-After and the usual error body")
	void aResetIsServiceUnavailable() {
		MockServerWebExchange exchange = exchange();
		// What the 2,000-shopper run logged: Netty's native transport reports a reset as an IOException.
		Throwable reset = new RuntimeException("wrapped", new IOException("recvAddress(..) failed: Connection reset by peer"));

		handler.handle(exchange, reset).block();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
		String body = exchange.getResponse().getBodyAsString().block();
		assertThat(body).contains("\"status\":503").contains("\"code\":\"UPSTREAM_UNAVAILABLE\"")
				.contains("\"path\":\"/api/v1/users/sessions\"").contains("\"correlationId\":\"corr-1\"");
	}

	@Test
	@DisplayName("a refused connection, a stopped service, is the same answer")
	void aRefusedConnectionIsServiceUnavailable() {
		MockServerWebExchange exchange = exchange();

		handler.handle(exchange, new ConnectException("Connection refused")).block();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	@DisplayName("anything else goes on to the default handler untouched, so a real fault still reads as one")
	void otherErrorsPassThrough() {
		MockServerWebExchange exchange = exchange();
		IllegalStateException bug = new IllegalStateException("a bug in the gateway");

		assertThatThrownBy(() -> handler.handle(exchange, bug).block()).isSameAs(bug);
		assertThatThrownBy(() -> handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)).block())
				.isInstanceOf(ResponseStatusException.class);
		assertThat(exchange.getResponse().getStatusCode()).isNull();
	}

	@Test
	@DisplayName("a response already on its way cannot be rewritten, so it is left to fail")
	void aCommittedResponseIsLeftAlone() {
		MockServerWebExchange exchange = exchange();
		exchange.getResponse().setComplete().block();
		IllegalStateException reset = new IllegalStateException("upstream", new IOException("Connection reset by peer"));

		assertThatThrownBy(() -> handler.handle(exchange, reset).block()).isSameAs(reset);
	}

	private static MockServerWebExchange exchange() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/users/sessions"));
		exchange.getResponse().getHeaders().set(CorrelationId.HEADER, "corr-1");
		return exchange;
	}
}
