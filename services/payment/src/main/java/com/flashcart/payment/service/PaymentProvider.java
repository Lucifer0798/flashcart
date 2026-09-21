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

	/**
	 * Reverse a capture.
	 *
	 * <p>No timeout case, and that asymmetry with {@link #charge} is deliberate rather than an
	 * omission. A charge that times out leaves the platform unable to say whether the customer was
	 * billed, which is why {@code PaymentTimedOut} exists and why the saga refuses to release stock on
	 * it. A refund that times out is not the same problem: the money is the customer's either way, so
	 * a refund whose outcome is unknown can simply be attempted again. Modelling a state for it would
	 * be modelling a decision nobody has to make.
	 *
	 * @param idempotencyKey the refund's own key, not the charge's — a provider given the capture's
	 *                       key would be entitled to read this as a repeat of the capture
	 * @param providerReference what the provider called the original capture. A refund names the
	 *                       charge it reverses; without it the provider is being asked to send money
	 *                       to a customer rather than to give a specific payment back.
	 */
	Outcome refund(String idempotencyKey, String providerReference, BigDecimal amount, String currency);

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
