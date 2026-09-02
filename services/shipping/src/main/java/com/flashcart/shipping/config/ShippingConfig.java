package com.flashcart.shipping.config;

import java.time.Clock;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShippingConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public OpenAPI shippingOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart Shipping API")
				.version("v1")
				.description("Shipments and carrier tracking. A shipment exists only once payment has "
						+ "settled."));
	}
}
