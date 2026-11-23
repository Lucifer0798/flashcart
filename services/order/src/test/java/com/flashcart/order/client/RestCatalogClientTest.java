package com.flashcart.order.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What checkout makes of catalog's answers. See ADR 0078. */
class RestCatalogClientTest {

	private HttpServer catalog;

	@AfterEach
	void stop() {
		if (catalog != null) {
			catalog.stop(0);
		}
	}

	private RestCatalogClient answering(int status, String body) throws IOException {
		catalog = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		catalog.createContext("/", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		catalog.start();
		return new RestCatalogClient(RestClient.create("http://127.0.0.1:" + catalog.getAddress().getPort()));
	}

	@Test
	@DisplayName("a price catalog answered is a price")
	void readsAPrice() throws IOException {
		RestCatalogClient client = answering(200, """
				{"sku":"AUD-HP-001","name":"Headphones","effectivePrice":179.00,"currency":"USD","onFlashSale":false}""");

		assertThat(client.priceOf("AUD-HP-001").effectivePrice()).isEqualByComparingTo("179.00");
	}

	@Test
	@DisplayName("an answer that cannot be read is catalog unavailable, a retryable 503, not a 500")
	void anUnreadableAnswerIsUnavailable() throws IOException {
		// What a restock rush produced: a 200 whose JSON broke off.
		RestCatalogClient client = answering(200, "{\"sku\":\"AUD-HP-001\",\"effectivePri");

		assertThatThrownBy(() -> client.priceOf("AUD-HP-001")).isInstanceOf(CatalogUnavailableException.class);
	}

	@Test
	@DisplayName("a refusal from catalog is catalog unavailable too, and an unknown SKU is still not found")
	void refusalsAndUnknownSkus() throws IOException {
		assertThatThrownBy(() -> answering(503, "{}").priceOf("AUD-HP-001"))
				.isInstanceOf(CatalogUnavailableException.class);
		stop();
		assertThatThrownBy(() -> answering(404, "{}").priceOf("GHOST-1"))
				.isInstanceOf(com.flashcart.common.error.ResourceNotFoundException.class);
	}
}
