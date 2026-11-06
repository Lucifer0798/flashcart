package com.flashcart.shipping;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** A customer downloading what the shipping service holds about them. See ADR 0066. */
class DataExportIT extends AbstractShippingIT {

	private static final String PATH = "/api/v1/shipping/_export/mine";

	@Test
	@DisplayName("the file holds the caller's parcels and when staff looked, and nobody else's")
	@SuppressWarnings("unchecked")
	void theFileHoldsTheCallersOwn() {
		createFor("export-s-mine", "FC-EXPSHP01");
		createFor("export-s-other", "FC-EXPSHP02");
		jdbc.update("""
				insert into operator_access_log (operator_id, action, resource_id, customer_id) values
				  ('support-1', 'READ_SHIPMENT', 'FC-EXPSHP01', 'export-s-mine'),
				  ('support-1', 'READ_SHIPMENT', 'FC-EXPSHP02', 'export-s-other')""");

		// The parameter is ignored: whose comes from the token.
		ResponseEntity<Map> export = as("export-s-mine", PATH + "?customerId=export-s-other");

		assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.isEqualTo("attachment; filename=\"flashcart-shipping-export.json\"");
		assertThat(export.getBody()).containsEntry("service", "shipping").containsEntry("customerId", "export-s-mine");
		assertThat((List<Map<String, Object>>) export.getBody().get("shipments")).singleElement()
				.satisfies(shipment -> assertThat(shipment).containsEntry("orderNumber", "FC-EXPSHP01"));
		assertThat((List<Map<String, Object>>) export.getBody().get("staffAccess")).singleElement()
				.satisfies(entry -> assertThat(entry).containsEntry("resourceId", "FC-EXPSHP01")
						.containsOnlyKeys("action", "description", "resourceId", "readAt"));
		assertThat(export.getBody().toString()).doesNotContain("FC-EXPSHP02").doesNotContain("support-1");
	}

	@Test
	@DisplayName("downloading your own file is not recorded as staff looking")
	void ownReadIsNotRecorded() {
		createFor("export-s-quiet", "FC-EXPSHP03");

		assertThat(as("export-s-quiet", PATH).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(auditRows("export-s-quiet")).isZero();
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
}
