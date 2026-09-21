package com.flashcart.payment;

import com.flashcart.common.event.message.PaymentRefunded;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giving the money back.
 *
 * <p>Its own class rather than more of {@link PaymentIT}, which is already the longest suite here and
 * is about the three outcomes of taking money. These are about the fourth thing that can happen to a
 * payment afterwards, and they share nothing with those beyond the fixture.
 *
 * <p>The case each of these guards is the same one: a refund that runs when it should not, or twice.
 * Paying a customer back is cheap to get wrong and expensive to notice.
 */
class PaymentRefundIT extends AbstractPaymentIT {

	@Test
	@DisplayName("a completed capture is reversed, and the charge is still on the record")
	void completedPaymentIsRefunded() {
		Payment charged = charge("179.00");
		events.clear();

		Payment refunded = payments.refund(charged.getOrderNumber(), "customer cancelled",
				"refund:" + charged.getOrderId());

		assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(refunded.getRefundReference()).startsWith("simref_");
		assertThat(refunded.getRefundedAt()).isNotNull();
		// settledAt survives the refund. It records that this customer was charged, which is the one
		// fact a refunded payment most needs to keep -- and the thing a support call starts from.
		assertThat(refunded.getSettledAt()).isNotNull();

		PaymentRefunded event = events.require(PaymentRefunded.class);
		assertThat(event.amount()).isEqualByComparingTo("179.00");
		assertThat(event.providerReference()).isEqualTo(refunded.getRefundReference());
	}

	@Test
	@DisplayName("a second command does not pay out twice")
	void refundIsIdempotent() {
		Payment charged = charge("179.00");
		String key = "refund:" + charged.getOrderId();
		payments.refund(charged.getOrderNumber(), "customer cancelled", key);
		events.clear();

		Payment again = payments.refund(charged.getOrderNumber(), "customer cancelled", key);

		assertThat(again.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		// Re-published rather than ignored, for the same reason a duplicated charge re-publishes: the
		// likelier explanation for a repeated command is that the first answer was what got lost.
		assertThat(events.countOf(PaymentRefunded.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("a refund the provider refuses leaves the money visibly still held")
	void refusedRefundIsItsOwnState() {
		Payment charged = charge("100.77");
		assertThat(charged.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		events.clear();

		Payment refused = payments.refund(charged.getOrderNumber(), "customer cancelled",
				"refund:" + charged.getOrderId());

		// Not FAILED. A charge that never happened and a refund that would not go through are
		// opposite problems, and folding them together hides money the platform owes among charges
		// it never took.
		assertThat(refused.getStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
		assertThat(refused.getFailureCode()).isEqualTo("REFUND_REFUSED");
		assertThat(refused.getRefundedAt()).isNull();

		// Nothing is published. The order is already cancelled, and an event saying the money went
		// back would be the one statement in this system that is simply untrue.
		assertThat(events.published(PaymentRefunded.class)).isFalse();
	}

	@Test
	@DisplayName("a declined charge has nothing to refund, and nothing is sent")
	void declinedPaymentIsNotRefunded() {
		Payment declined = charge("100.13");
		assertThat(declined.getStatus()).isEqualTo(PaymentStatus.FAILED);
		events.clear();

		Payment after = payments.refund(declined.getOrderNumber(), "customer cancelled",
				"refund:" + declined.getOrderId());

		assertThat(after.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(events.published(PaymentRefunded.class)).isFalse();
	}

	@Test
	@DisplayName("a timed-out charge is not refunded, because nobody knows whether it landed")
	void timedOutPaymentIsNotRefunded() {
		Payment timedOut = charge("100.99");
		assertThat(timedOut.getStatus()).isEqualTo(PaymentStatus.TIMED_OUT);
		events.clear();

		Payment after = payments.refund(timedOut.getOrderNumber(), "customer cancelled",
				"refund:" + timedOut.getOrderId());

		// The dangerous one. Refunding here would send money for a charge that may never have
		// happened, which is the mirror image of the reason the saga will not release stock on a
		// timeout. The reconciler settles it first; only then is there something to reverse.
		assertThat(after.getStatus()).isEqualTo(PaymentStatus.TIMED_OUT);
		assertThat(events.published(PaymentRefunded.class)).isFalse();
	}
}
