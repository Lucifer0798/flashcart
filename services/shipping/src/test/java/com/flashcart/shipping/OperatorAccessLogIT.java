package com.flashcart.shipping;

import java.util.List;
import java.util.Map;

import com.flashcart.shipping.domain.Shipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of an operator reading somebody else's parcel, per ADR 0025.
 *
 * <p>Dispatch and deliver are deliberately not recorded here. This table answers "who read whose
 * data", not "what did the warehouse do", and conflating the two would make it useless for both.
 */
class OperatorAccessLogIT extends AbstractShippingIT {

	// --- what an operator read is recorded (ADR 0025) -------------------------------------------------

	

	@Test
	@DisplayName("an operator can read who accessed a customer's parcels; a customer cannot")
	void accessLogIsReadableByOperatorsOnly() {
		createFor("audit-reader-a", "FC-READLOG9");
		// One recorded access to find. The suite's interceptor signs this as an operator.
		rest.getForEntity("/api/v1/shipments/order/FC-READLOG9", Map.class);

		List<Map<String, Object>> log = rest.getForObject(
				"/api/v1/shipping/_access-log?customerId=audit-reader-a", List.class);
		// The parcel read, plus this read of the log, recorded before it answered.
		assertThat(log).extracting(e -> e.get("action"))
				.containsExactly("READ_ACCESS_LOG", "READ_SHIPMENT");

		ResponseEntity<Map> refused = rest.exchange(
				"/api/v1/shipping/_access-log?customerId=audit-reader-a", HttpMethod.GET,
				new HttpEntity<>(bearer("audit-reader-a")), Map.class);
		// Closed by OperatorFilter's default-deny rather than by a rule anybody wrote for it -- and on
		// the singular prefix, because GET /api/v1/shipments/* would have opened it to every customer.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an operator tracking somebody else's parcel leaves a record of who looked")
	void operatorReadIsRecorded() {
		createFor("audit-owner-a", "FC-AUDIT01");

		// No explicit header, so the suite's operator interceptor supplies one.
		assertThat(rest.getForEntity("/api/v1/shipments/order/FC-AUDIT01", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-a'");
		assertThat(row).containsEntry("action", "READ_SHIPMENT");
		assertThat(row).containsEntry("resource_id", "FC-AUDIT01");
	}

	@Test
	@DisplayName("a customer tracking their own parcel leaves nothing")
	void ownReadIsNotRecorded() {
		createFor("audit-owner-b", "FC-AUDIT02");

		assertThat(as("audit-owner-b", "/api/v1/shipments/order/FC-AUDIT02").getStatusCode())
				.isEqualTo(HttpStatus.OK);

		assertThat(auditRows("audit-owner-b")).isZero();
	}

	@Test
	@DisplayName("nor does a refused read: nothing was disclosed, so there is nothing to record")
	void refusedReadIsNotRecorded() {
		createFor("audit-owner-c", "FC-AUDIT03");

		assertThat(as("audit-stranger", "/api/v1/shipments/order/FC-AUDIT03").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(auditRows("audit-owner-c")).isZero();
	}

	@Test
	@DisplayName("a tracking-number lookup by an operator is recorded under the number they used")
	void operatorTrackingLookupIsRecorded() {
		Shipment shipment = createFor("audit-owner-d", "FC-AUDIT04");

		assertThat(rest.getForEntity("/api/v1/shipments/" + shipment.getTrackingNumber(), Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap(
				"select * from operator_access_log where customer_id = 'audit-owner-d'");
		assertThat(row).containsEntry("resource_id", shipment.getTrackingNumber());
	}

	@Test
	@DisplayName("if the access cannot be recorded the parcel is not shown")
	void anUnrecordableReadIsRefused() {
		createFor("audit-owner-e", "FC-AUDIT05");

		// Renamed rather than dropped so the suite continues afterwards.
		jdbc.execute("alter table operator_access_log rename to operator_access_log_hidden");
		try {
			ResponseEntity<Map> response = rest.getForEntity("/api/v1/shipments/order/FC-AUDIT05", Map.class);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {
			jdbc.execute("alter table operator_access_log_hidden rename to operator_access_log");
		}
	}

	@Test
	@DisplayName("dispatch is unaffected: it is an operator action, not a read of somebody's data")
	void dispatchIsNotAudited() {
		Shipment shipment = createFor("audit-owner-g", "FC-AUDIT07");

		assertThat(rest.postForEntity("/api/v1/shipments/" + shipment.getTrackingNumber() + "/dispatch",
				null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Worth stating: this table answers "who read whose data", not "what did the warehouse do".
		// Conflating the two would make it useless for both questions.
		assertThat(auditRows("audit-owner-g")).isZero();
	}
}
