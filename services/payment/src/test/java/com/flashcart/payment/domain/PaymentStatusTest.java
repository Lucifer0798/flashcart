package com.flashcart.payment.domain;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which states hold the customer's money.
 *
 * <p>A unit test rather than another case in {@code PaymentRefundIT}, because the question is about
 * the enum and not about a running service: refunding the wrong state is not a bug that needs a
 * database to demonstrate.
 */
class PaymentStatusTest {

	@Test
	@DisplayName("only a state that is holding money can be refunded")
	void refundableStatesAreTheOnesHoldingMoney() {
		assertThat(Arrays.stream(PaymentStatus.values()).filter(PaymentStatus::isRefundable))
				.containsExactlyInAnyOrder(PaymentStatus.COMPLETED, PaymentStatus.REFUND_FAILED);
	}

	@Test
	@DisplayName("a refused refund is not a dead end, because the money is still ours to return")
	void refundFailedRemainsRefundable() {
		// The distinction this makes is the point of the state existing. REFUNDED is finished and a
		// second attempt would pay twice; REFUND_FAILED is money the platform is still holding for an
		// order it has already told the customer is cancelled, so it has to stay reachable.
		assertThat(PaymentStatus.REFUND_FAILED.isRefundable()).isTrue();
		assertThat(PaymentStatus.REFUNDED.isRefundable()).isFalse();
	}

	@Test
	@DisplayName("an unsettled charge is never refundable")
	void unsettledChargesAreNotRefundable() {
		// TIMED_OUT is the one that matters. Nobody knows whether that charge landed, so reversing it
		// could send money for a capture that never happened -- the mirror of why the saga refuses to
		// release stock on a timeout.
		assertThat(PaymentStatus.TIMED_OUT.isRefundable()).isFalse();
		assertThat(PaymentStatus.PENDING.isRefundable()).isFalse();
		assertThat(PaymentStatus.FAILED.isRefundable()).isFalse();
	}
}
