package com.flashcart.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.OperatorAccessRetention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of an operator reading somebody else's payment, and how long it is kept.
 *
 * <p>ADR 0025 added the table and ADR 0026 the sweeper, and they belong together because they are
 * the two halves of one question: what is written down, and for how long.
 *
 * <p>The retention tests clear the table first. The sweep is a table-wide DELETE, so without that
 * they pass or fail on whatever the recording tests happened to leave behind — a result about test
 * ordering wearing the costume of a result about retention, which is exactly what happened the first
 * time they ran.
 */
class OperatorAccessLogIT extends AbstractPaymentIT {

	// --- reading the log back (ADR 0034) --------------------------------------------------------------

	@Test
	@DisplayName("an operator can read who accessed a customer's payments")
	void accessLogIsReadable() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-READLOG1", "audit-reader-a", new BigDecimal("15.00"), "USD",
				orderId.toString());
		// One recorded access to find.
		rest.getForEntity("/api/v1/payments/order/FC-READLOG1", Map.class);

		ResponseEntity<List> log = rest.getForEntity(
				"/api/v1/payment/_access-log?customerId=audit-reader-a", List.class);

		assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> entries = log.getBody();
		// Two: the payment read, and this read of the log.
		assertThat(entries).hasSize(2);
		assertThat(entries).extracting(e -> e.get("action"))
				.containsExactly("READ_ACCESS_LOG", "READ_PAYMENT");
		assertThat(entries.get(1)).containsEntry("resourceId", "FC-READLOG1");
		assertThat(entries.get(0)).containsEntry("operatorId", "ops-test");
	}

	@Test
	@DisplayName("reading the log is recorded first, so the newest entry is the request that asked")
	void readingTheLogIsItselfRecorded() {
		jdbc.update("insert into operator_access_log (operator_id, action, customer_id) "
				+ "values ('ops-old', 'READ_PAYMENT', 'audit-reader-b')");

		rest.getForEntity("/api/v1/payment/_access-log?customerId=audit-reader-b", List.class);

		// ADR 0025's invariant is that the row exists before the data is disclosed, so a read of this
		// table appears in its own response rather than only in the next one.
		assertThat(auditRows("audit-reader-b")).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from operator_access_log "
				+ "where customer_id = 'audit-reader-b' and action = 'READ_ACCESS_LOG'", Long.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a signed-in customer cannot read the log, and is told so plainly")
	void customerCannotReadTheAccessLog() {
		ResponseEntity<Map> refused = rest.exchange(
				"/api/v1/payment/_access-log?customerId=audit-reader-c", HttpMethod.GET,
				new HttpEntity<>(bearer("audit-reader-c")), Map.class);

		// 403, not 404. The oracle argument that makes somebody else's order a 404 does not apply:
		// this path is fixed and published, and the caller already knew the customer id they sent.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(refused.getBody()).containsEntry("code", "OPERATOR_REQUIRED");
		// And the refusal discloses nothing, so it records nothing.
		assertThat(auditRows("audit-reader-c")).isZero();
	}

	@Test
	@DisplayName("a customer nobody has looked at has an empty log, not an error")
	void untouchedCustomerHasAnEmptyLog() {
		ResponseEntity<List> log = rest.getForEntity(
				"/api/v1/payment/_access-log?customerId=audit-reader-d", List.class);

		assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
		// One entry: this request. Nothing had looked at them before it.
		assertThat(log.getBody()).hasSize(1);
	}

	// --- what an operator read is recorded (ADR 0025) -------------------------------------------------

	

	@Test
	@DisplayName("an operator reading somebody else's payment leaves a record of who looked")
	void operatorReadIsRecorded() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-AUDIT01", "audit-owner-a", new BigDecimal("15.00"), "USD",
				orderId.toString());

		// No explicit header, so the suite's operator interceptor supplies one.
		assertThat(rest.getForEntity("/api/v1/payments/order/FC-AUDIT01", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-a'");
		assertThat(row).containsEntry("operator_id", "ops-test");
		assertThat(row).containsEntry("action", "READ_PAYMENT");
		assertThat(row).containsEntry("resource_id", "FC-AUDIT01");
	}

	@Test
	@DisplayName("a customer reading their own leaves nothing -- auditing that would bury the signal")
	void ownReadIsNotRecorded() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-AUDIT02", "audit-owner-b", new BigDecimal("15.00"), "USD",
				orderId.toString());

		assertThat(as("audit-owner-b", "/api/v1/payments/order/FC-AUDIT02").getStatusCode())
				.isEqualTo(HttpStatus.OK);

		assertThat(auditRows("audit-owner-b")).isZero();
	}

	@Test
	@DisplayName("nor does a refused read: nothing was disclosed, so there is nothing to record")
	void refusedReadIsNotRecorded() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-AUDIT03", "audit-owner-c", new BigDecimal("15.00"), "USD",
				orderId.toString());

		assertThat(as("audit-stranger", "/api/v1/payments/order/FC-AUDIT03").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(auditRows("audit-owner-c")).isZero();
	}

	@Test
	@DisplayName("an operator listing another customer is recorded, with no single resource")
	void operatorListingIsRecorded() {
		ResponseEntity<List> response = rest.exchange("/api/v1/payments?customerId=audit-owner-d",
				HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), List.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-d'");
		assertThat(row).containsEntry("action", "READ_PAYMENT_LIST");
		assertThat(row.get("resource_id")).isNull();
	}

	@Test
	@DisplayName("if the access cannot be recorded the data is not served")
	void anUnrecordableReadIsRefused() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-AUDIT05", "audit-owner-e", new BigDecimal("15.00"), "USD",
				orderId.toString());

		// The property, tested the only way that actually demonstrates it: take the table away.
		// Renamed rather than dropped so the suite continues afterwards.
		jdbc.execute("alter table operator_access_log rename to operator_access_log_hidden");
		try {
			ResponseEntity<Map> response = rest.getForEntity("/api/v1/payments/order/FC-AUDIT05", Map.class);

			// A 500 is the correct answer here and the honest one: the service cannot do what it
			// promises, so it refuses rather than quietly serving an unrecorded read.
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {
			jdbc.execute("alter table operator_access_log_hidden rename to operator_access_log");
		}
	}

	@Test
	@DisplayName("and the owner can still read it once recording works again")
	void theReadWorksAgainAfterwards() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-AUDIT06", "audit-owner-f", new BigDecimal("15.00"), "USD",
				orderId.toString());

		assertThat(rest.getForEntity("/api/v1/payments/order/FC-AUDIT06", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	// --- expiring those records, once somebody has said to (ADR 0026) -------------------------------

	/**
	 * The sweep is a table-wide DELETE, so these tests start from an empty table. Without this they
	 * pass or fail depending on what the audit tests above happened to leave behind, which is a
	 * result about test ordering wearing the costume of a result about retention.
	 */
	

	

	@Test
	@DisplayName("with no retention configured nothing is deleted, however old it is")
	void keepsEverythingByDefault() {
		clearAuditLog();
		recordAgedAccess("retention-a", 4000);

		// The bean the service actually builds when the property is unset.
		OperatorAccessRetention keepForever = new OperatorAccessRetention(jdbc, null, 500);

		assertThat(keepForever.sweep()).isZero();
		assertThat(auditRows("retention-a")).isEqualTo(1);
	}

	@Test
	@DisplayName("a zero or negative window is treated as 'keep', not as 'delete everything'")
	void zeroMeansKeep() {
		clearAuditLog();
		recordAgedAccess("retention-b", 4000);

		assertThat(new OperatorAccessRetention(jdbc, Duration.ZERO, 500).sweep()).isZero();
		assertThat(new OperatorAccessRetention(jdbc, Duration.ofDays(-1), 500).sweep()).isZero();
		assertThat(auditRows("retention-b")).isEqualTo(1);
	}

	@Test
	@DisplayName("once configured, records older than the window go and newer ones stay")
	void deletesOnlyBeyondTheWindow() {
		clearAuditLog();
		recordAgedAccess("retention-c", 100);
		recordAgedAccess("retention-d", 10);

		int deleted = new OperatorAccessRetention(jdbc, Duration.ofDays(30), 500).sweep();

		assertThat(deleted).isEqualTo(1);
		assertThat(auditRows("retention-c")).isZero();
		assertThat(auditRows("retention-d")).isEqualTo(1);
	}

	@Test
	@DisplayName("the sweep is batched, so a first run against a neglected table cannot lock it all")
	void deletesInBatches() {
		clearAuditLog();
		for (int i = 0; i < 5; i++) {
			recordAgedAccess("retention-e", 100 + i);
		}

		OperatorAccessRetention sweeper = new OperatorAccessRetention(jdbc, Duration.ofDays(30), 2);

		assertThat(sweeper.sweep()).isEqualTo(2);
		assertThat(auditRows("retention-e")).isEqualTo(3);
		assertThat(sweeper.sweep()).isEqualTo(2);
		assertThat(sweeper.sweep()).isEqualTo(1);
		assertThat(auditRows("retention-e")).isZero();
	}
}
