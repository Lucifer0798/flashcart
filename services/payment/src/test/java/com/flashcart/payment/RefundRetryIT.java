package com.flashcart.payment;

import com.flashcart.common.event.message.PaymentRefunded;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.service.RefundRetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job that tries again when the provider would not give the money back.
 *
 * <p>Its own class rather than more of {@link PaymentRefundIT}: those tests are about what one
 * refund does, and these are about what happens over several, driven by a scheduler rather than by a
 * command.
 *
 * <p>Two things are simulated by writing to the row rather than by waiting. Elapsed time, because
 * the retry delay is minutes and {@code updated_at} is written by Hibernate rather than from the
 * injected clock — a test that moved the clock would be moving something the query does not read.
 * And the attempt count, so the cap can be reached without sitting through every attempt before it.
 */
class RefundRetryIT extends AbstractPaymentIT {

	@Autowired
	private RefundRetryService retries;

	@Test
	@DisplayName("a refused refund is tried again once the delay has passed")
	void refusedRefundIsRetried() {
		Payment refused = refuseARefund();
		assertThat(refused.getRefundAttempts()).isEqualTo(1);
		overdue(refused);

		assertThat(retries.retryBatch()).isEqualTo(1);

		// Refused again, because the simulated provider decides from the amount and the amount has
		// not changed. What matters is that it was asked: the attempt count moved.
		Payment after = payments.get(refused.getId());
		assertThat(after.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
		assertThat(after.getRefundAttempts()).isEqualTo(2);
	}

	@Test
	@DisplayName("a retry that the provider accepts refunds the money and publishes it")
	void retryCanSucceed() {
		Payment refused = refuseARefund();
		overdue(refused);
		// ADR 0015 makes the amount the way to choose the provider's answer, so this is how a
		// transient refusal is expressed: the same row, an amount the provider will now reverse.
		jdbc.update("update payments set amount = 100.50 where id = ?", refused.getId());
		events.clear();

		assertThat(retries.retryBatch()).isEqualTo(1);

		Payment after = payments.get(refused.getId());
		assertThat(after.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(after.getRefundedAt()).isNotNull();
		// The attempt count keeps the refusal that came before it, which is the honest record.
		assertThat(after.getRefundAttempts()).isEqualTo(1);
		assertThat(events.published(PaymentRefunded.class)).isTrue();
	}

	@Test
	@DisplayName("a refund that has only just been refused is left alone")
	void freshRefusalIsNotRetried() {
		Payment refused = refuseARefund();

		retries.retryBatch();

		// Asserted on this payment rather than on the batch count. These suites share a database, so
		// "the batch did nothing" would depend on what every other test happened to leave behind --
		// a check that passes for a reason unrelated to what it claims.
		assertThat(payments.get(refused.getId()).getRefundAttempts()).isEqualTo(1);
	}

	@Test
	@DisplayName("retrying stops at the cap, and stopping is not deciding the money is not owed")
	void retriesStopAtTheCap() {
		Payment refused = refuseARefund();
		// One short of the cap of five, so the next attempt is the last one.
		jdbc.update("update payments set refund_attempts = 4 where id = ?", refused.getId());
		overdue(refused);

		assertThat(retries.retryBatch()).isEqualTo(1);
		Payment atCap = payments.get(refused.getId());
		assertThat(atCap.getRefundAttempts()).isEqualTo(5);

		// Now beyond it, so nothing claims it again however long it waits.
		overdue(atCap);
		retries.retryBatch();
		assertThat(payments.get(refused.getId()).getRefundAttempts()).isEqualTo(5);

		// But the money is still owed and the domain still says so. The cap is this job's policy,
		// not a statement about the payment.
		assertThat(atCap.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
		assertThat(atCap.getStatus().isRefundable()).isTrue();
	}

	@Test
	@DisplayName("a refund that went through is never claimed")
	void refundedPaymentIsNotRetried() {
		Payment charged = charge("179.00");
		Payment refunded = refundOf(charged);
		assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		overdue(refunded);
		events.clear();

		retries.retryBatch();

		// Untouched, and nothing re-published. A retry here would be a second payout, and it would
		// show up as a new provider reference -- which is a better witness than the timestamp,
		// because PostgreSQL stores microseconds and the in-memory Instant carries more than that.
		Payment after = payments.get(refunded.getId());
		assertThat(after.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(after.getRefundReference()).isEqualTo(refunded.getRefundReference());
		assertThat(events.published(PaymentRefunded.class)).isFalse();
	}

	// --- helpers ------------------------------------------------------------------------------------

	/** A payment whose capture succeeded and whose refund the provider refused. */
	private Payment refuseARefund() {
		Payment charged = charge("100.77");
		assertThat(charged.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		Payment refused = refundOf(charged);
		assertThat(refused.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
		return refused;
	}

	private Payment refundOf(Payment charged) {
		return payments.refund(charged.getOrderNumber(), "customer cancelled",
				"refund:" + charged.getOrderId());
	}

	/** Makes the row look as though the retry delay has already elapsed. */
	private void overdue(Payment payment) {
		jdbc.update("update payments set updated_at = now() - interval '1 hour' where id = ?",
				payment.getId());
	}
}
