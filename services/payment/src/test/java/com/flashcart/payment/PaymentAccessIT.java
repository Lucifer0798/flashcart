package com.flashcart.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.payment.domain.Payment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose payment it is, per ADR 0023.
 *
 * <p>Almost every test here is about what a <em>second</em> customer cannot see. That is deliberate:
 * the filter can only tell that somebody is signed in, so the ownership check in the handler is the
 * whole of the protection, and a suite that only proved the owner's own reads work would pass just
 * as happily with no check at all.
 */
class PaymentAccessIT extends AbstractPaymentIT {

	// --- whose payment it is (ADR 0023) ---------------------------------------------------------------

	/** A request as a specific shopper, overriding the suite's operator interceptor. */
	

	@Test
	@DisplayName("a shopper can read the payment for their own order")
	void shopperReadsTheirOwn() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-MINE001", "shopper-a", new BigDecimal("31.00"), "USD",
				orderId.toString());

		ResponseEntity<Map> response = as("shopper-a", "/api/v1/payments/order/FC-MINE001");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "COMPLETED");
	}

	@Test
	@DisplayName("somebody else's is 404, not 403 -- a 403 would confirm the order number is real")
	void anothersPaymentIsNotFound() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-THEIRS1", "shopper-a", new BigDecimal("31.00"), "USD",
				orderId.toString());

		ResponseEntity<Map> response = as("shopper-b", "/api/v1/payments/order/FC-THEIRS1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// Byte for byte what an order number that never existed returns, which is the point.
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
	}

	@Test
	@DisplayName("and the same by payment id")
	void anothersPaymentByIdIsNotFound() {
		UUID orderId = UUID.randomUUID();
		Payment payment = payments.charge(orderId, "FC-THEIRS2", "shopper-a", new BigDecimal("31.00"),
				"USD", orderId.toString());

		ResponseEntity<Map> response = as("shopper-b", "/api/v1/payments/" + payment.getId());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("a listing returns the caller's own payments and nobody else's")
	void listingIsScopedToTheCaller() {
		UUID mine = UUID.randomUUID();
		payments.charge(mine, "FC-LIST001", "shopper-c", new BigDecimal("12.00"), "USD", mine.toString());
		UUID theirs = UUID.randomUUID();
		payments.charge(theirs, "FC-LIST002", "shopper-d", new BigDecimal("12.00"), "USD", theirs.toString());

		ResponseEntity<List> response = rest.exchange("/api/v1/payments", HttpMethod.GET,
				new HttpEntity<>(bearer("shopper-c")), List.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().get(0).toString()).contains("FC-LIST001");
	}

	@Test
	@DisplayName("asking for another customer's listing is refused, not quietly answered with your own")
	void askingForAnothersListingIsForbidden() {
		ResponseEntity<Map> response = as("shopper-e", "/api/v1/payments?customerId=shopper-f");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).containsEntry("code", "OPERATOR_REQUIRED");
	}

	@Test
	@DisplayName("naming yourself is not asking for somebody else")
	void askingForYourOwnListingByNameIsFine() {
		ResponseEntity<List> response = rest.exchange("/api/v1/payments?customerId=shopper-g",
				HttpMethod.GET, new HttpEntity<>(bearer("shopper-g")), List.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("an operator still reads anybody's, which is the point of the role")
	void operatorReadsAnybodys() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-OPSREAD", "shopper-h", new BigDecimal("44.00"), "USD",
				orderId.toString());

		// No explicit header, so the suite's operator interceptor supplies one.
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/payments/order/FC-OPSREAD", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("no token is still 401: opening this to customers did not open it to nobody")
	void anonymousIsStillRefused() {
		HttpHeaders none = new HttpHeaders();
		none.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token");
		ResponseEntity<Map> response = rest.exchange("/api/v1/payments", HttpMethod.GET,
				new HttpEntity<>(none), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
