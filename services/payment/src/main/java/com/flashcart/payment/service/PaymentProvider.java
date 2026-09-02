package com.flashcart.payment.service;

import java.math.BigDecimal;

/**
 * The card network, as far as this service is concerned.
 *
 * <p>An interface so the simulation below can be swapped for a real gateway without the service that
 * uses it changing — and, more usefully today, so the outcomes that matter can actually be provoked.
 */
public interface PaymentProvider {

	/**
	 * @param idempotencyKey passed through to the provider. Real gateways accept one for exactly this
	 *                       reason: a retried charge must not become a second charge.
	 * @throws ProviderTimeoutException when the provider did not answer. Distinct from a decline,
	 *                                  because the charge may still land.
	 */
	Outcome charge(String idempotencyKey, BigDecimal amount, String currency, String customerId);

	/** @param declineCode the provider's own code, null on success */
	record Outcome(boolean approved, String providerReference, String declineCode, String declineReason) {

		public static Outcome approved(String reference) {
			return new Outcome(true, reference, null, null);
		}

		public static Outcome declined(String code, String reason) {
			return new Outcome(false, null, code, reason);
		}
	}

	/** The provider did not answer in time. The charge may or may not have gone through. */
	class ProviderTimeoutException extends RuntimeException {

		public ProviderTimeoutException(String message) {
			super(message);
		}
	}
}
