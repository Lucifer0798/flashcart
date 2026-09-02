package com.flashcart.payment.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.payment.api.dto.PaymentResponse;
import com.flashcart.payment.config.PaymentProperties;
import com.flashcart.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, deliberately.
 *
 * <p>There is no endpoint here that charges a card: payment is initiated by a command on the bus and
 * nowhere else, so the one operation that moves money has exactly one entry point.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "What was charged, and what the provider said")
public class PaymentController {

	private final PaymentService payments;
	private final PaymentProperties properties;
	private final String applicationName;
	private final String version;

	public PaymentController(PaymentService payments, PaymentProperties properties,
			@Value("${spring.application.name}") String applicationName,
			@Value("${flashcart.version:0.1.0-SNAPSHOT}") String version) {
		this.payments = payments;
		this.properties = properties;
		this.applicationName = applicationName;
		this.version = version;
	}

	@GetMapping("/{paymentId}")
	@Operation(summary = "One payment attempt")
	public PaymentResponse get(@PathVariable UUID paymentId) {
		return PaymentResponse.from(payments.get(paymentId));
	}

	@GetMapping("/order/{orderNumber}")
	@Operation(summary = "The payment attempt for an order")
	public PaymentResponse forOrder(@PathVariable String orderNumber) {
		return PaymentResponse.from(payments.getByOrderNumber(orderNumber));
	}

	@GetMapping
	@Operation(summary = "A customer's payment attempts, newest first")
	public List<PaymentResponse> forCustomer(@RequestParam String customerId) {
		return payments.forCustomer(customerId).stream().map(PaymentResponse::from).toList();
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
