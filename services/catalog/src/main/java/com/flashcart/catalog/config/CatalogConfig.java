package com.flashcart.catalog.config;

import java.time.Clock;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogConfig {

	/**
	 * Injected rather than reaching for {@code Instant.now()}, because a flash sale is defined
	 * entirely by a time window: the boundary behaviour is only testable if the clock is a seam.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public OpenAPI catalogOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart Catalog API")
				.version("v1")
				.description("Products, categories and flash-sale definitions. "
						+ "Stock levels are owned by the inventory service, not this one."));
	}
}
