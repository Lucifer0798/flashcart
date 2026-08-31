package com.flashcart.inventory.config;

import java.time.Clock;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
@EnableScheduling
public class InventoryConfig {

	/**
	 * Injected rather than calling {@code Instant.now()} inline. Every interesting behaviour in this
	 * service is a function of time — a hold expires, a sweeper reclaims — and none of it is
	 * testable at the boundary unless the clock is a seam.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public OpenAPI inventoryOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart Inventory API")
				.version("v1")
				.description("Stock, reservations and reservation expiry. This service owns how many "
						+ "units are actually available; catalog owns what a product is."));
	}
}
