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
	TIMED_OUT,

	/** The money went back. Terminal: there is nothing left to take or return. */
	REFUNDED,

	/**
	 * The capture stood, and the provider would not reverse it.
	 *
	 * <p>The worst state in this enum, and it needs a name of its own precisely because it is easy to
	 * miss: the order is already cancelled, so every other part of the platform believes the customer
	 * has been made whole, and only this row disagrees. Folding it into {@code FAILED} would hide money
	 * the platform owes among charges that never happened.
	 */
	REFUND_FAILED;

	public boolean isSettled() {
		return this == COMPLETED || this == FAILED;
	}

	/** True when money was taken and has not been given back. Only these can be refunded. */
	public boolean isRefundable() {
		return this == COMPLETED || this == REFUND_FAILED;
	}
}
