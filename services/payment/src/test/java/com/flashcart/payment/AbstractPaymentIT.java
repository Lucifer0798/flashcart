package com.flashcart.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.service.PaymentReconciliationService;
import com.flashcart.payment.service.PaymentService;
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
	 * Shared setup for the payment integration tests.
 *
	 * <p>One PostgreSQL container and one Spring context across every subclass — the annotations live
	 * here and are therefore identical, so Spring's context cache reuses it rather than paying for a
	 * second boot and a second container. Same arrangement as {@code AbstractInventoryIT}, and the
	 * reason splitting these suites costs nothing.
 *
	 * <p>The suites were split because one class had grown to five hundred lines covering the payment
	 * outcomes, idempotency, reconciliation, ownership, auditing and retention at once. A failure in it
	 * named the file rather than the concern, and the helpers of one concern were in scope for all the
	 * others — which is how the retention tests came to depend on what the audit tests happened to leave
	 * in the table.
	 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingEventPublisher.class)
@TestPropertySource(properties = {
		// Driven explicitly in the test that cares; on its own timer it would time out attempts
		// mid-assertion elsewhere.
		"flashcart.payment.reconciler.enabled=false",
		// These suites record what the service decides, so their own publisher must win. Turning the
		// queue off leaves the consumer-side dedup beans in place, which the listeners still need.
		"flashcart.outbox.enabled=false",
		"spring.kafka.listener.auto-startup=false"
})
abstract class AbstractPaymentIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	PaymentService payments;

	@Autowired
	PaymentReconciliationService reconciler;

	@Autowired
	RecordingEventPublisher.Recorder events;

	@Autowired
	TestRestTemplate rest;

	@Autowired
	AccessTokens tokens;

	@Autowired
	JdbcTemplate jdbc;

	/**
	 * Signs every request as an operator.
	 *
	 * <p>Most of payment's HTTP surface is back-office, so without this the suites would exercise the
	 * filter rather than the service and "404 for an unknown payment" would quietly become "403 for
	 * everyone".
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

	Payment charge(String amount) {
		UUID orderId = UUID.randomUUID();
		return payments.charge(orderId, "FC-TEST" + orderId.toString().substring(0, 4), "cust-1",
				new BigDecimal(amount), "USD", orderId.toString());
	}

	ResponseEntity<Map> as(String customerId, String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION,
				"Bearer " + tokens.issue(customerId, customerId + "@example.test"));
		return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
	}

	HttpHeaders bearer(String customerId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION,
				"Bearer " + tokens.issue(customerId, customerId + "@example.test"));
		return headers;
	}

	Long auditRows(String customerId) {
		return jdbc.queryForObject(
				"select count(*) from operator_access_log where customer_id = ?", Long.class, customerId);
	}

	void clearAuditLog() {
		jdbc.update("delete from operator_access_log");
	}

	void recordAgedAccess(String customerId, int daysAgo) {
		jdbc.update("""
				insert into operator_access_log
				    (operator_id, action, resource_id, customer_id, correlation_id, read_at)
				values (?, 'READ_PAYMENT', ?, ?, null, now() - make_interval(days => ?))""",
				"ops-retention", "FC-AGED" + daysAgo, customerId, daysAgo);
	}
}
