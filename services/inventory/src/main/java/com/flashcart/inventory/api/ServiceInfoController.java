package com.flashcart.inventory.api;

import java.util.Map;

import com.flashcart.inventory.config.InventoryProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identifies the build behind this gateway route.
 *
 * <p>Kept from the Phase 1 skeleton — "which build is actually running here" stays worth asking once
 * a service is real — but now also reports the operational settings that change how the service
 * behaves under load, so a surprising result during a load test can be checked rather than guessed
 * at.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Service info")
public class ServiceInfoController {

	private final String applicationName;
	private final String version;
	private final InventoryProperties properties;

	public ServiceInfoController(@Value("${spring.application.name}") String applicationName,
			@Value("${flashcart.version:0.1.0-SNAPSHOT}") String version,
			InventoryProperties properties) {
		this.applicationName = applicationName;
		this.version = version;
		this.properties = properties;
	}

	@GetMapping("/_info")
	@Operation(summary = "Which build is behind this route, and how is it configured")
	public Map<String, String> info() {
		return Map.of(
				"service", applicationName,
				"version", version,
				"status", "live",
				"implementedIn", "Phase 3",
				"reservationStrategy", properties.strategy().name(),
				"reservationTtl", properties.reservationTtl().toString(),
				"sweeperEnabled", String.valueOf(properties.sweeper().enabled()),
				// Reported so a load run can be labelled with the configuration that actually ran,
				// rather than the one it was asked for. A measurement labelled with an intention is
				// a number that gets quoted later and cannot be checked.
				"availabilityGate", String.valueOf(properties.gate().enabled()));
	}
}
