package com.flashcart.inventory.config;

import java.time.Clock;

import com.flashcart.inventory.service.AvailabilityGate;
import com.flashcart.inventory.service.DisabledAvailabilityGate;
import com.flashcart.inventory.service.RedisAvailabilityGate;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
@EnableScheduling
public class InventoryConfig {

	private static final Logger log = LoggerFactory.getLogger(InventoryConfig.class);

	/**
	 * Injected rather than calling {@code Instant.now()} inline. Every interesting behaviour in this
	 * service is a function of time — a hold expires, a sweeper reclaims — and none of it is
	 * testable at the boundary unless the clock is a seam.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * The availability gate, or a no-op when Redis is switched off.
	 *
	 * <p>Switchable because Phase 10 needs to measure the same reserve path with the gate in and out
	 * to say what it actually bought — and because a deployment without Redis must still be a
	 * correct deployment, just a slower one.
	 */
	@Bean
	public AvailabilityGate availabilityGate(InventoryProperties properties,
			ObjectProvider<StringRedisTemplate> redis) {
		StringRedisTemplate template = redis.getIfAvailable();
		if (!properties.gate().enabled() || template == null) {
			log.info("Availability gate disabled; every reservation goes straight to the database");
			return new DisabledAvailabilityGate();
		}
		return new RedisAvailabilityGate(template, properties.gate().ttl());
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
