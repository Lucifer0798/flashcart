package com.flashcart.payment;

import java.util.List;
import java.util.Map;

import com.flashcart.common.event.message.PaymentRefunded;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.service.AbandonedRefunds;
import com.flashcart.payment.service.RefundRetryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recording an abandoned refund as paid outside the platform. See ADR 0041.
 *
 * <p>The cap is reached by writing the attempt count rather than by sitting through five refusals,
 * as {@link RefundRetryIT} does and for the same reason.
 */
class RefundSettlementIT extends AbstractPaymentIT {

	@Autowired
	private AbandonedRefunds abandoned;

	@Autowired
	private RefundRetryService retries;

	@Autowired
	private MeterRegistry meters;

	@Test
	@DisplayName("an abandoned refund recorded as paid elsewhere stops being owed, and says who and how")
	void settlingAnAbandonedRefund() {
		Payment owed = abandonARefund();
		long before = abandoned.count();
		events.clear();

		ResponseEntity<Map> response = settle(owed, "BANK-TX-0042", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "REFUNDED_OUTSIDE")
				.containsEntry("refundReference", "BANK-TX-0042");
		assertThat(response.getBody().get("refundedAt")).isNotNull();

		// Attributed to the operator's token subject, which the response deliberately does not show
		// the customer.
		assertThat(jdbc.queryForObject("select refund_settled_by from payments where id = ?",
				String.class, owed.getId())).isEqualTo("ops-test");

		// One fewer customer owed money, which is the number the critical alert now watches.
		assertThat(abandoned.count()).isEqualTo(before - 1);

		// No PaymentRefunded: that event says the provider reversed the capture, which it did not.
		assertThat(events.published(PaymentRefunded.class)).isFalse();
	}

	@Test
	@DisplayName("while the retry job may still ask the provider, recording a manual refund is refused")
	void notWhileTheJobIsStillTrying() {
		Payment refused = refuseARefund();

		ResponseEntity<Map> response = settle(refused, "BANK-TX-TOO-EARLY", null);

		// The case that could pay the customer twice: a transfer made by hand, then the provider
		// accepting the job's next attempt.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "REFUND_NOT_ABANDONED");
		assertThat(payments.get(refused.getId()).getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
	}

	@Test
	@DisplayName("a payment that owes nothing cannot be recorded as refunded")
	void nothingOwed() {
		Payment charged = charge("100.00");

		ResponseEntity<Map> response = settle(charged, "BANK-TX-NOTHING", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "REFUND_NOT_OWED");
		assertThat(payments.get(charged.getId()).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
	}

	@Test
	@DisplayName("repeating the same record is harmless; a second, different reference is refused")
	void idempotentForTheSameReferenceOnly() {
		Payment owed = abandonARefund();
		assertThat(settle(owed, "BANK-TX-1", null).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(settle(owed, "BANK-TX-1", null).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Two references for one refund is a typo or a customer paid twice. Neither should overwrite.
		ResponseEntity<Map> different = settle(owed, "BANK-TX-2", null);
		assertThat(different.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(different.getBody()).containsEntry("code", "REFUND_ALREADY_SETTLED");
		assertThat(payments.get(owed.getId()).getRefundReference()).isEqualTo("BANK-TX-1");
	}

	@Test
	@DisplayName("once recorded, neither the retry job nor a late refund command touches the provider")
	void settledIsTerminal() {
		Payment owed = abandonARefund();
		settle(owed, "BANK-TX-FINAL", null);
		jdbc.update("update payments set updated_at = now() - interval '1 hour' where id = ?", owed.getId());

		retries.retryBatch();
		Payment afterLate = payments.refund(owed.getOrderNumber(), "late command", "refund:" + owed.getOrderId());

		assertThat(afterLate.getStatus()).isEqualTo(PaymentStatus.REFUNDED_OUTSIDE);
		assertThat(afterLate.getRefundReference()).isEqualTo("BANK-TX-FINAL");
		assertThat(afterLate.getStatus().isRefundable()).isFalse();
	}

	@Test
	@DisplayName("the gauge reports what is owed now, so the alert resolves when it is paid and not before")
	void gaugeFollowsTheRows() {
		Payment owed = abandonARefund();
		abandoned.refresh();
		double owing = gauge();
		assertThat(owing).isEqualTo(abandoned.count()).isPositive();

		settle(owed, "BANK-TX-GAUGE", null);

		// Refreshed by the settlement itself rather than on the next tick, so the alert does not page
		// for a minute about money that has already been recorded as paid.
		assertThat(gauge()).isEqualTo(owing - 1);
	}

	@Test
	@DisplayName("a refund the job is still retrying is not counted as abandoned; one at the cap is")
	void onlyAbandonedRefundsCount() {
		long before = abandoned.count();

		Payment refused = refuseARefund();
		// Owed, but something is still trying to pay it, which RefundRefused already warns about.
		// Counting it here would page critically for every refusal the job is about to fix.
		assertThat(abandoned.count()).isEqualTo(before);

		jdbc.update("update payments set refund_attempts = 5 where id = ?", refused.getId());
		assertThat(abandoned.count()).isEqualTo(before + 1);
	}

	@Test
	@DisplayName("only an operator may record it: not support, not the customer")
	void operatorOnly() {
		Payment owed = abandonARefund();

		HttpHeaders support = new HttpHeaders();
		support.set(HttpHeaders.AUTHORIZATION, "Bearer "
				+ tokens.issue("support-1", "support@example.test", List.of(AccessTokens.SUPPORT)));
		assertThat(settle(owed, "BANK-TX-SUP", support).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// The customer owed the money, of all people, must not be able to mark it paid.
		assertThat(settle(owed, "BANK-TX-ME", bearer(owed.getCustomerId())).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);

		assertThat(payments.get(owed.getId()).getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
	}

	@Test
	@DisplayName("a record without a reference is not a record")
	void referenceRequired() {
		Payment owed = abandonARefund();

		assertThat(settle(owed, " ", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payments.get(owed.getId()).getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
	}

	@Test
	@DisplayName("an order with no payment is a 404")
	void unknownOrder() {
		ResponseEntity<Map> response = rest.exchange("/api/v1/payment/_refunds/FC-NOSUCH/settled-outside",
				HttpMethod.POST, new HttpEntity<>(Map.of("reference", "BANK-TX-X")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private ResponseEntity<Map> settle(Payment payment, String reference, HttpHeaders headers) {
		return rest.exchange("/api/v1/payment/_refunds/" + payment.getOrderNumber() + "/settled-outside",
				HttpMethod.POST, new HttpEntity<>(Map.of("reference", reference),
						headers == null ? new HttpHeaders() : headers), Map.class);
	}

	private double gauge() {
		return meters.get("flashcart.payment.refunds.abandoned.outstanding").gauge().value();
	}

	private Payment refuseARefund() {
		Payment charged = charge("100.77");
		Payment refused = payments.refund(charged.getOrderNumber(), "customer cancelled",
				"refund:" + charged.getOrderId());
		assertThat(refused.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
		return refused;
	}

	/** Refused, and at the cap: the retry job will not try again. */
	private Payment abandonARefund() {
		Payment refused = refuseARefund();
		jdbc.update("update payments set refund_attempts = 5 where id = ?", refused.getId());
		return payments.get(refused.getId());
	}
}
