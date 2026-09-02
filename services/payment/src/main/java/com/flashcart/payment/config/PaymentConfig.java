package com.flashcart.payment.config;

import java.time.Clock;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
@EnableScheduling
public class PaymentConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	public OpenAPI paymentOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart Payment API")
				.version("v1")
				.description("Payment attempts and their outcomes. Holds no card details: it records "
						+ "what was asked for and what the provider said."));
	}
}
