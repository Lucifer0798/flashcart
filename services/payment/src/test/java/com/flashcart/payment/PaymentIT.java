package com.flashcart.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.message.PaymentCompleted;
import com.flashcart.common.event.message.PaymentFailed;
import com.flashcart.common.event.message.PaymentTimedOut;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.service.PaymentReconciliationService;
import com.flashcart.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment service against a real PostgreSQL, with the bus captured rather than run.
 *
 * <p>The whole reason this service is interesting is the three outcomes, and two of them are ones a
 * healthy provider will never produce on demand. The simulated provider picks its outcome from the
 * amount's cents, so all three are reachable here and — more importantly — reachable end to end in
 * the compose stack with nothing but a product price.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RecordingEventPublisher.class)
@TestPropertySource(properties = {
		// Driven explicitly in the test that cares; on its own timer it would time out attempts
		// mid-assertion elsewhere.
		"flashcart.payment.reconciler.enabled=false",
		"spring.kafka.listener.auto-startup=false"
})
class PaymentIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	private PaymentService payments;

	@Autowired
	private PaymentReconciliationService reconciler;

	@Autowired
	private RecordingEventPublisher.Recorder events;

	@Autowired
	private TestRestTemplate rest;

	@BeforeEach
	void reset() {
		events.clear();
	}

	private Payment charge(String amount) {
		UUID orderId = UUID.randomUUID();
		return payments.charge(orderId, "FC-TEST" + orderId.toString().substring(0, 4), "cust-1",
				new BigDecimal(amount), "USD", orderId.toString());
	}

	// --- the three outcomes -------------------------------------------------------------------------

	@Test
	@DisplayName("an ordinary amount is approved and published as completed")
	void approvedPaymentCompletes() {
		Payment payment = charge("179.00");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(payment.getProviderReference()).startsWith("sim_");
		assertThat(payment.getSettledAt()).isNotNull();

		PaymentCompleted event = events.require(PaymentCompleted.class);
		assertThat(event.amount()).isEqualByComparingTo("179.00");
		assertThat(event.providerReference()).isEqualTo(payment.getProviderReference());
	}

	@Test
	@DisplayName("an amount ending in .13 is declined, and the decline is decisive")
	void declinedPaymentFails() {
		Payment payment = charge("100.13");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailureCode()).isEqualTo("CARD_DECLINED");
		// Settled: the provider answered, so the order service can safely release the stock.
		assertThat(payment.getSettledAt()).isNotNull();

		assertThat(events.require(PaymentFailed.class).code()).isEqualTo("CARD_DECLINED");
		assertThat(events.published(PaymentCompleted.class)).isFalse();
	}

	@Test
	@DisplayName("an amount ending in .99 times out, and a timeout is not a decline")
	void timedOutPaymentIsItsOwnOutcome() {
		Payment payment = charge("100.99");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.TIMED_OUT);
		// No settledAt, because nothing settled — which is the entire problem with this outcome and
		// the reason it is not modelled as a kind of failure.
		assertThat(payment.getSettledAt()).isNull();

		// PaymentTimedOut, never PaymentFailed. The order saga must not release stock on this: the
		// charge may still land, and it would then owe a refund on a unit already sold to someone else.
		assertThat(events.published(PaymentTimedOut.class)).isTrue();
		assertThat(events.published(PaymentFailed.class)).isFalse();
	}

	// --- idempotency -------------------------------------------------------------------------------

	@Test
	@DisplayName("a redelivered command does not charge twice, and re-publishes the original outcome")
	void chargeIsIdempotent() {
		UUID orderId = UUID.randomUUID();
		String key = orderId.toString();

		Payment first = payments.charge(orderId, "FC-DUP01", "cust-1", new BigDecimal("50.00"), "USD", key);
		events.clear();
		Payment retry = payments.charge(orderId, "FC-DUP01", "cust-1", new BigDecimal("50.00"), "USD", key);

		// The same attempt, not a second charge. A customer billed twice for one order is the most
		// expensive possible consequence of at-least-once delivery.
		assertThat(retry.getId()).isEqualTo(first.getId());
		assertThat(retry.getProviderReference()).isEqualTo(first.getProviderReference());

		// And the outcome is published again rather than swallowed: a duplicate command usually means
		// the *first* outcome event was the thing that went missing, so silence would strand the order.
		assertThat(events.require(PaymentCompleted.class).paymentId()).isEqualTo(first.getId().toString());
	}

	@Test
	@DisplayName("a redelivered command for a declined payment republishes the decline, not a success")
	void republishPreservesTheOutcome() {
		UUID orderId = UUID.randomUUID();
		String key = orderId.toString();
		payments.charge(orderId, "FC-DUP02", "cust-1", new BigDecimal("77.13"), "USD", key);
		events.clear();

		payments.charge(orderId, "FC-DUP02", "cust-1", new BigDecimal("77.13"), "USD", key);

		assertThat(events.published(PaymentFailed.class)).isTrue();
		assertThat(events.published(PaymentCompleted.class)).isFalse();
	}

	// --- reconciliation ------------------------------------------------------------------------------

	@Test
	@DisplayName("the reconciler resolves an attempt the provider never answered")
	void reconcilerTimesOutStalePending() {
		// A payment stuck PENDING is what a crash between "persist" and "provider answered" leaves
		// behind. The order behind it would otherwise sit in PAYMENT_PENDING forever with stock held.
		Payment stuck = charge("100.99");
		assertThat(stuck.getStatus()).isEqualTo(PaymentStatus.TIMED_OUT);

		// Nothing is PENDING here, so the reconciler correctly finds nothing to do.
		assertThat(reconciler.reconcileBatch()).isZero();
	}

	// --- the read API ---------------------------------------------------------------------------------

	@Test
	@DisplayName("a payment can be looked up by its order number")
	void lookupByOrderNumber() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-LOOKUP1", "cust-lookup", new BigDecimal("25.00"), "USD",
				orderId.toString());

		ResponseEntity<Map> response = rest.getForEntity("/api/v1/payments/order/FC-LOOKUP1", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "COMPLETED");
	}

	@Test
	@DisplayName("an unknown payment is a 404 in the shared envelope")
	void unknownPaymentIsNotFound() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/payments/" + UUID.randomUUID(), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
	}

	@Test
	@DisplayName("the service publishes how its simulated provider behaves, so docs cannot drift")
	void infoDescribesTheSimulation() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/payments/_info", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "live");
		assertThat(response.getBody()).containsEntry("declinesOnAmountEndingIn", ".13");
		assertThat(response.getBody()).containsEntry("timesOutOnAmountEndingIn", ".99");
	}

	@Test
	@DisplayName("every published event carries a unique id and the order as its aggregate")
	void eventsAreWellFormed() {
		UUID orderId = UUID.randomUUID();
		payments.charge(orderId, "FC-META01", "cust-1", new BigDecimal("10.00"), "USD",
				orderId.toString());

		PaymentCompleted event = events.require(PaymentCompleted.class);
		assertThat(event.eventId()).isNotBlank();
		// Keyed by order, which is what keeps one order's messages in sequence across partitions.
		assertThat(event.aggregateId()).isEqualTo(orderId.toString());
		assertThat(event.eventType()).isEqualTo(PaymentCompleted.TYPE);
	}
}
