package com.flashcart.order.config;

import java.time.Clock;

import com.flashcart.order.client.CatalogClient;
import com.flashcart.order.client.RestCatalogClient;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OrderProperties.class)
@EnableScheduling
public class OrderConfig {

	/**
	 * Injected rather than called inline. Reservation expiry is the interesting behaviour in this
	 * service and it is a function of time; without a clock seam it is only testable by sleeping.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public CatalogClient catalogClient(OrderProperties properties) {
		return new RestCatalogClient(restClient(properties.catalogUrl(), properties));
	}

	/**
	 * Timeouts are set explicitly on both clients.
	 *
	 * <p>A client with no read timeout waits forever, and one slow downstream then consumes every
	 * request thread in this service until nothing can be served at all — the classic way a single
	 * struggling dependency takes a whole platform down with it.
	 */
	private static RestClient restClient(String baseUrl, OrderProperties properties) {
		// Boot 4 renamed ClientHttpRequestFactorySettings to HttpClientSettings and moved it into
		// the spring-boot-http-client module.
		HttpClientSettings settings = HttpClientSettings.defaults()
				.withTimeouts(properties.requestTimeout(), properties.requestTimeout());
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
				.build();
	}

	@Bean
	public OpenAPI orderOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart Order API")
				.version("v1")
				.description("The order aggregate and its state machine. Prices come from catalog and "
						+ "stock from inventory; this service orchestrates and duplicates neither."));
	}
}
