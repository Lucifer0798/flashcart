package com.flashcart.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param declineOnCents  an amount whose cents equal this is declined by the simulated provider
 * @param timeoutOnCents  an amount whose cents equal this times out
 * @param pendingTimeout  how long an attempt may sit PENDING before the reconciler calls it timed out
 * @param reconciler      settings for the job that resolves attempts the provider never answered
 */
@ConfigurationProperties(prefix = "flashcart.payment")
public record PaymentProperties(
		int declineOnCents,
		int timeoutOnCents,
		Duration pendingTimeout,
		Reconciler reconciler) {

	public PaymentProperties {
		declineOnCents = declineOnCents <= 0 ? 13 : declineOnCents;
		timeoutOnCents = timeoutOnCents <= 0 ? 99 : timeoutOnCents;
		pendingTimeout = pendingTimeout == null ? Duration.ofMinutes(2) : pendingTimeout;
		reconciler = reconciler == null ? new Reconciler(true, 200) : reconciler;
	}

	public record Reconciler(boolean enabled, int batchSize) {

		public Reconciler {
			batchSize = batchSize <= 0 ? 200 : batchSize;
		}
	}
}
