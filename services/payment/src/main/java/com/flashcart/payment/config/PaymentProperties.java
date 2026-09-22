package com.flashcart.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param declineOnCents  an amount whose cents equal this is declined by the simulated provider
 * @param timeoutOnCents  an amount whose cents equal this times out
 * @param refundDeclineOnCents an amount whose cents equal this cannot be refunded. Its own value
 *                        rather than a reuse of {@code declineOnCents}, which names an amount that
 *                        never captures and so can never reach a refund at all
 * @param pendingTimeout  how long an attempt may sit PENDING before the reconciler calls it timed out
 * @param reconciler      settings for the job that resolves attempts the provider never answered
 * @param refundRetry     settings for the job that re-attempts refunds the provider refused
 */
@ConfigurationProperties(prefix = "flashcart.payment")
public record PaymentProperties(
		int declineOnCents,
		int timeoutOnCents,
		int refundDeclineOnCents,
		Duration pendingTimeout,
		Reconciler reconciler,
		RefundRetry refundRetry) {

	public PaymentProperties {
		declineOnCents = declineOnCents <= 0 ? 13 : declineOnCents;
		timeoutOnCents = timeoutOnCents <= 0 ? 99 : timeoutOnCents;
		refundDeclineOnCents = refundDeclineOnCents <= 0 ? 77 : refundDeclineOnCents;
		pendingTimeout = pendingTimeout == null ? Duration.ofMinutes(2) : pendingTimeout;
		reconciler = reconciler == null ? new Reconciler(true, 200) : reconciler;
		refundRetry = refundRetry == null ? new RefundRetry(true, 50, 5, Duration.ofMinutes(5))
				: refundRetry;
	}

	public record Reconciler(boolean enabled, int batchSize) {

		public Reconciler {
			batchSize = batchSize <= 0 ? 200 : batchSize;
		}
	}

	/**
	 * @param maxAttempts how many refusals to accept before giving up on a payment. Counts the first
	 *                    attempt, so 5 means the original and four retries. A cap rather than
	 *                    forever, because most refusals are permanent — a capture too old to reverse,
	 *                    a closed account — and retrying those to the end of time is a way to keep
	 *                    the provider busy and the alert meaningless.
	 * @param delay       how long to leave a refused refund before trying again. Long, because the
	 *                    kind of failure that resolves itself resolves in minutes, not seconds.
	 */
	public record RefundRetry(boolean enabled, int batchSize, int maxAttempts, Duration delay) {

		public RefundRetry {
			batchSize = batchSize <= 0 ? 50 : batchSize;
			maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
			delay = delay == null || delay.isNegative() ? Duration.ofMinutes(5) : delay;
		}
	}
}
