package com.flashcart.inventory;

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

/** A customer downloading what the inventory service holds about them. See ADR 0066. */
class DataExportIT extends AbstractInventoryIT {

	private static final String PATH = "/api/v1/inventory/_export/mine";

	@Test
	@DisplayName("the file holds the caller's waitlist places, held stock and sale caps, and nobody else's")
	@SuppressWarnings("unchecked")
	void theFileHoldsTheCallersOwn() {
		String soldOut = uniqueSku("EXP-OUT");
		createStock(soldOut, 0);
		join("export-i-mine", soldOut);
		join("export-i-other", soldOut);

		String onSale = uniqueSku("EXP-SALE");
		createStock(onSale, 20);
		UUID flashSaleId = UUID.randomUUID();
		allocate(flashSaleId, onSale, 10, 5);
		assertThat(reserve(uniqueKey("exp"), "export-i-mine", flashSaleId, onSale, 2, null).getStatusCode().is2xxSuccessful())
				.isTrue();
		assertThat(reserve(uniqueKey("exp"), "export-i-other", flashSaleId, onSale, 3, null).getStatusCode().is2xxSuccessful())
				.isTrue();

		// The parameter is ignored: whose comes from the token.
		ResponseEntity<Map> export = rest.exchange(PATH + "?customerId=export-i-other", HttpMethod.GET,
				new HttpEntity<>(as("export-i-mine")), Map.class);

		assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.isEqualTo("attachment; filename=\"flashcart-inventory-export.json\"");
		assertThat(export.getBody()).containsEntry("service", "inventory").containsEntry("customerId", "export-i-mine");
		assertThat((List<Map<String, Object>>) export.getBody().get("waitlist")).singleElement()
				.satisfies(entry -> assertThat(entry).containsEntry("sku", soldOut));
		assertThat((List<Map<String, Object>>) export.getBody().get("reservations")).singleElement()
				.satisfies(reservation -> assertThat(reservation).containsEntry("customerId", "export-i-mine"));
		assertThat((List<Map<String, Object>>) export.getBody().get("saleLimits")).singleElement()
				.satisfies(limit -> assertThat(limit).containsEntry("sku", onSale).containsEntry("consumedUnits", 2));
		assertThat(export.getBody().toString()).doesNotContain("export-i-other");
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

	private HttpHeaders as(String customerId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION,
				"Bearer " + tokens.issue(customerId, customerId + "@example.test", List.of()));
		return headers;
	}

	private void join(String customerId, String sku) {
		assertThat(rest.exchange("/api/v1/inventory/waitlist", HttpMethod.POST,
				new HttpEntity<>(Map.of("sku", sku), as(customerId)), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
	}
}
