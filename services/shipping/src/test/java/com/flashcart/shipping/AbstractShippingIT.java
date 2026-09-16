package com.flashcart.shipping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.service.ShipmentService;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared setup for the shipping integration tests.
 *
 * <p>One PostgreSQL container and one Spring context across every subclass — the annotations live
 * here and are therefore identical, so Spring's context cache reuses it. Same arrangement as
 * {@code AbstractInventoryIT} and {@code AbstractPaymentIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingEventPublisher.class)
@TestPropertySource(properties = {
		"spring.kafka.listener.auto-startup=false",
		// These suites record what the service decides, so their own publisher must win. Turning the
		// queue off leaves the consumer-side dedup beans in place, which the listeners still need.
		"flashcart.outbox.enabled=false",
})
abstract class AbstractShippingIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	ShipmentService shipments;

	@Autowired
	RecordingEventPublisher.Recorder events;

	@Autowired
	TestRestTemplate rest;

	@Autowired
	AccessTokens tokens;

	@Autowired
	JdbcTemplate jdbc;

	/**
	 * Signs every request as an operator: dispatching and delivering a parcel are warehouse actions,
	 * not shopper ones (ADR 0022).
	 */
	@BeforeEach
	void signInAsOperator() {
		String token = tokens.issue("ops-test", "ops@example.test", List.of(AccessTokens.OPERATOR));
		rest.getRestTemplate().getInterceptors().clear();
		rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
			// Only when the caller has not said who it is. The ownership tests act as a specific
			// shopper, and an interceptor that overwrote that would quietly turn every one of them
			// into another operator test that passes for the wrong reason.
			if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
				request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			}
			return execution.execute(request, body);
		});
	}

	@BeforeEach
	void reset() {
		events.clear();
	}

	Shipment create(UUID orderId, String orderNumber) {
		return shipments.create(orderId, orderNumber, "cust-1",
				List.of(new ShipmentService.RequestedLine("AUD-HP-001", 1)));
	}

	HttpHeaders bearer(String customerId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION,
				"Bearer " + tokens.issue(customerId, customerId + "@example.test"));
		return headers;
	}

	ResponseEntity<Map> as(String customerId, String path) {
		return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(customerId)), Map.class);
	}

	Shipment createFor(String customerId, String orderNumber) {
		return shipments.create(UUID.randomUUID(), orderNumber, customerId,
				List.of(new ShipmentService.RequestedLine("AUD-HP-001", 1)));
	}

	Long auditRows(String customerId) {
		return jdbc.queryForObject(
				"select count(*) from operator_access_log where customer_id = ?", Long.class, customerId);
	}
}
