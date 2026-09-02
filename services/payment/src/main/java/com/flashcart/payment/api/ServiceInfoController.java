package com.flashcart.payment.api;

import java.util.Map;

import com.flashcart.payment.config.PaymentProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identifies the build behind this gateway route.
 *
 * <p>On {@code /api/v1/payment/_info}, singular, which is the convention every service follows —
 * the plural paths are for resources. Worth keeping uniform: a health-sweep across the platform
 * should be a loop over service names, not a lookup table of exceptions.
 */
@RestController
@RequestMapping("/api/v1/payment")
@Tag(name = "Service info")
public class ServiceInfoController {

	private final String applicationName;
	private final String version;
	private final PaymentProperties properties;

	public ServiceInfoController(@Value("${spring.application.name}") String applicationName,
			@Value("${flashcart.version:0.1.0-SNAPSHOT}") String version, PaymentProperties properties) {
		this.applicationName = applicationName;
		this.version = version;
		this.properties = properties;
	}

	@GetMapping("/_info")
	@Operation(summary = "Which build is behind this route, and how the simulated provider behaves")
	public Map<String, String> info() {
		return Map.of(
				"service", applicationName,
				"version", version,
				"status", "live",
				"implementedIn", "Phase 6",
				"provider", "simulated",
				// Published so the compose stack and the docs cannot drift from the code.
				"declinesOnAmountEndingIn", "." + properties.declineOnCents(),
				"timesOutOnAmountEndingIn", "." + properties.timeoutOnCents());
	}
}
