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
 */
@ConfigurationProperties(prefix = "flashcart.payment")
public record PaymentProperties(
		int declineOnCents,
		int timeoutOnCents,
		int refundDeclineOnCents,
		Duration pendingTimeout,
		Reconciler reconciler) {

	public PaymentProperties {
		declineOnCents = declineOnCents <= 0 ? 13 : declineOnCents;
		timeoutOnCents = timeoutOnCents <= 0 ? 99 : timeoutOnCents;
		refundDeclineOnCents = refundDeclineOnCents <= 0 ? 77 : refundDeclineOnCents;
		pendingTimeout = pendingTimeout == null ? Duration.ofMinutes(2) : pendingTimeout;
		reconciler = reconciler == null ? new Reconciler(true, 200) : reconciler;
	}

	public record Reconciler(boolean enabled, int batchSize) {

		public Reconciler {
			batchSize = batchSize <= 0 ? 200 : batchSize;
		}
	}
}
