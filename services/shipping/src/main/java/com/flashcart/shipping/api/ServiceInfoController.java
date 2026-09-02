package com.flashcart.shipping.api;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identifies the build behind this gateway route, on the singular path every service uses. */
@RestController
@RequestMapping("/api/v1/shipping")
@Tag(name = "Service info")
public class ServiceInfoController {

	private final String applicationName;
	private final String version;

	public ServiceInfoController(@Value("${spring.application.name}") String applicationName,
			@Value("${flashcart.version:0.1.0-SNAPSHOT}") String version) {
		this.applicationName = applicationName;
		this.version = version;
	}

	@GetMapping("/_info")
	@Operation(summary = "Which build is behind this route")
	public Map<String, String> info() {
		return Map.of("service", applicationName, "version", version, "status", "live",
				"implementedIn", "Phase 6");
	}
}
