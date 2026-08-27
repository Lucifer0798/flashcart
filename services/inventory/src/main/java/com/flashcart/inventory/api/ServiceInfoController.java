package com.flashcart.inventory.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identifies this service over HTTP so the gateway route can be verified end to end before the
 * service has any behaviour of its own.
 *
 * <p>The real inventory API arrives in Phase 3; this endpoint stays, because "which build is actually
 * running behind this route" is a question worth being able to ask in every environment.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class ServiceInfoController {

	private final String applicationName;
	private final String version;

	public ServiceInfoController(@Value("${spring.application.name}") String applicationName,
			@Value("${flashcart.version:0.1.0-SNAPSHOT}") String version) {
		this.applicationName = applicationName;
		this.version = version;
	}

	@GetMapping("/_info")
	public Map<String, String> info() {
		return Map.of("service", applicationName, "version", version, "status", "skeleton", "implementedIn", "Phase 3");
	}
}
