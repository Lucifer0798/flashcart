package com.flashcart.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** A customer downloading what the payment service holds about them. See ADR 0066. */
class DataExportIT extends AbstractPaymentIT {

	private static final String PATH = "/api/v1/payment/_export/mine";

	@Test
	@DisplayName("the file holds the caller's payments and when staff looked, and nobody else's")
	@SuppressWarnings("unchecked")
	void theFileHoldsTheCallersOwn() {
		chargeFor("export-p-mine", "FC-EXPPAY01");
		chargeFor("export-p-other", "FC-EXPPAY02");
		jdbc.update("""
				insert into operator_access_log (operator_id, action, resource_id, customer_id) values
				  ('support-1', 'READ_PAYMENT', 'FC-EXPPAY01', 'export-p-mine'),
				  ('support-1', 'READ_PAYMENT', 'FC-EXPPAY02', 'export-p-other')""");

		// The parameter is ignored: whose comes from the token.
		ResponseEntity<Map> export = as("export-p-mine", PATH + "?customerId=export-p-other");

		assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.isEqualTo("attachment; filename=\"flashcart-payment-export.json\"");
		assertThat(export.getBody()).containsEntry("service", "payment").containsEntry("customerId", "export-p-mine");
		assertThat((List<Map<String, Object>>) export.getBody().get("payments")).singleElement()
				.satisfies(payment -> assertThat(payment).containsEntry("orderNumber", "FC-EXPPAY01"));
		assertThat((List<Map<String, Object>>) export.getBody().get("staffAccess")).singleElement()
				.satisfies(entry -> assertThat(entry).containsEntry("resourceId", "FC-EXPPAY01")
						.containsOnlyKeys("action", "description", "resourceId", "readAt"));
		assertThat(export.getBody().toString()).doesNotContain("FC-EXPPAY02").doesNotContain("support-1");
	}

	@Test
	@DisplayName("downloading your own file is not recorded as staff looking")
	void ownReadIsNotRecorded() {
		chargeFor("export-p-quiet", "FC-EXPPAY03");

		assertThat(as("export-p-quiet", PATH).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(auditRows("export-p-quiet")).isZero();
	}

	@Test
	@DisplayName("a token that does not verify is refused")
	void aForgedTokenIsRefused() {
		// A forged one, because this suite's template signs as an operator when given no header at all.
		HttpHeaders forged = new HttpHeaders();
		forged.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token");

		assertThat(rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(forged), Map.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private void chargeFor(String customerId, String orderNumber) {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, orderNumber, customerId, new BigDecimal("15.00"), "USD", orderId.toString());
	}
}
