package com.flashcart.order.api;

import java.util.Map;

import com.flashcart.order.config.OrderProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identifies the build behind this gateway route, and names the services it depends on.
 *
 * <p>Kept from the Phase 1 skeleton. "Which build is running, and what is it pointed at" stays worth
 * asking once a service is real — more so here than anywhere, since this service is the only one
 * that calls two others.
 */
@RestController
@RequestMapping("/api/v1/order")
@Tag(name = "Service info")
public class ServiceInfoController {

	private final String applicationName;
	private final String version;
	private final OrderProperties properties;

	public ServiceInfoController(@Value("${spring.application.name}") String applicationName,
			@Value("${flashcart.version:0.1.0-SNAPSHOT}") String version, OrderProperties properties) {
		this.applicationName = applicationName;
		this.version = version;
		this.properties = properties;
	}

	@GetMapping("/_info")
	@Operation(summary = "Which build is behind this route, and what it depends on")
	public Map<String, String> info() {
		return Map.of(
				"service", applicationName,
				"version", version,
				"status", "live",
				"implementedIn", "Phase 4",
				"catalogUrl", properties.catalogUrl(),
				"inventoryUrl", properties.inventoryUrl(),
				"requestTimeout", properties.requestTimeout().toString(),
				"reconcilerEnabled", String.valueOf(properties.reconciler().enabled()));
	}
}
