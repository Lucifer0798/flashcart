package com.flashcart.shipping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.service.ShipmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose parcel it is, per ADR 0023.
 *
 * <p>Mostly about what a <em>second</em> customer cannot see, because the filter can only tell that
 * somebody is signed in — the ownership check in the handler is the whole of the protection. A
 * tracking number gets the same treatment as an order number for a specific reason: it is printed on
 * a label, read aloud to carriers and pasted into emails, so it is the most widely handled
 * identifier this platform issues.
 */
class ShipmentAccessIT extends AbstractShippingIT {

	// --- whose parcel it is (ADR 0023) ----------------------------------------------------------------

	

	/** A request as a specific shopper, overriding the suite's operator interceptor. */
	

	

	@Test
	@DisplayName("a shopper can track their own parcel")
	void shopperTracksTheirOwn() {
		Shipment shipment = createFor("shopper-a", "FC-MINE001");

		ResponseEntity<Map> response = as("shopper-a", "/api/v1/shipments/" + shipment.getTrackingNumber());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("orderNumber", "FC-MINE001");
	}

	@Test
	@DisplayName("and by their own order number")
	void shopperReadsByOrderNumber() {
		createFor("shopper-a", "FC-MINE002");

		ResponseEntity<Map> response = as("shopper-a", "/api/v1/shipments/order/FC-MINE002");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("somebody else's tracking number is 404 -- it is the identifier printed on a label")
	void anothersTrackingIsNotFound() {
		Shipment shipment = createFor("shopper-a", "FC-THEIRS1");

		ResponseEntity<Map> response = as("shopper-b", "/api/v1/shipments/" + shipment.getTrackingNumber());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
	}

	@Test
	@DisplayName("and somebody else's order number is 404 too")
	void anothersOrderNumberIsNotFound() {
		createFor("shopper-a", "FC-THEIRS2");

		ResponseEntity<Map> response = as("shopper-b", "/api/v1/shipments/order/FC-THEIRS2");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("a listing returns the caller's own shipments and nobody else's")
	void listingIsScopedToTheCaller() {
		// Two lines deliberately. Fetching the collection to avoid a lazy-load blowup means a join,
		// and a join against a bag is how one shipment starts being returned twice.
		shipments.create(UUID.randomUUID(), "FC-LIST001", "shopper-c",
				List.of(new ShipmentService.RequestedLine("AUD-HP-001", 1),
						new ShipmentService.RequestedLine("AUD-HP-002", 2)));
		createFor("shopper-d", "FC-LIST002");

		ResponseEntity<List> response = rest.exchange("/api/v1/shipments", HttpMethod.GET,
				new HttpEntity<>(bearer("shopper-c")), List.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().get(0).toString()).contains("FC-LIST001");
	}

	@Test
	@DisplayName("asking for another customer's listing is refused, not quietly answered with your own")
	void askingForAnothersListingIsForbidden() {
		ResponseEntity<Map> response = as("shopper-e", "/api/v1/shipments?customerId=shopper-f");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).containsEntry("code", "OPERATOR_REQUIRED");
	}

	@Test
	@DisplayName("a customer may watch a parcel move, not move it: dispatch stays the warehouse's")
	void shopperCannotDispatch() {
		Shipment shipment = createFor("shopper-g", "FC-NODISP1");

		ResponseEntity<Map> response = rest.exchange(
				"/api/v1/shipments/" + shipment.getTrackingNumber() + "/dispatch",
				HttpMethod.POST, new HttpEntity<>(bearer("shopper-g")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("nor mark their own parcel delivered, which would be editing the warehouse's records")
	void shopperCannotDeliver() {
		Shipment shipment = createFor("shopper-h", "FC-NODELV1");

		ResponseEntity<Map> response = rest.exchange(
				"/api/v1/shipments/" + shipment.getTrackingNumber() + "/deliver",
				HttpMethod.POST, new HttpEntity<>(bearer("shopper-h")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an operator still reads and still dispatches")
	void operatorIsUnaffected() {
		Shipment shipment = createFor("shopper-i", "FC-OPSREAD");

		// No explicit header, so the suite's operator interceptor supplies one.
		assertThat(rest.getForEntity("/api/v1/shipments/order/FC-OPSREAD", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(rest.postForEntity("/api/v1/shipments/" + shipment.getTrackingNumber() + "/dispatch",
				null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("an unusable token is still 401: opening this to customers did not open it to nobody")
	void anonymousIsStillRefused() {
		HttpHeaders none = new HttpHeaders();
		none.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token");
		ResponseEntity<Map> response = rest.exchange("/api/v1/shipments", HttpMethod.GET,
				new HttpEntity<>(none), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
