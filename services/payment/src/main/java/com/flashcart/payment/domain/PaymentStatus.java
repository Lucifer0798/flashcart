package com.flashcart.payment.domain;

/** What the provider said, or did not say. */
public enum PaymentStatus {

	/** Sent to the provider; no answer yet. */
	PENDING,

	/** The money moved. */
	COMPLETED,

	/**
	 * The provider declined. Decisive: nothing was charged, so the order can safely give its stock
	 * back.
	 */
	FAILED,

	/**
	 * The provider did not answer in time.
	 *
	 * <p>Its own status, not a kind of {@link #FAILED}, because the two demand opposite responses.
	 * A decline means release the stock; a timeout means the charge may still land, so releasing it
	 * could sell the same unit twice and then owe a refund.
	 */
	TIMED_OUT;

	public boolean isSettled() {
		return this == COMPLETED || this == FAILED;
	}
}
