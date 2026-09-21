package com.flashcart.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;

/**
 * @param providerReference the provider's own identifier, null until it answers — and null forever
 *                          if it never did, which is what makes a TIMED_OUT attempt distinguishable
 *                          from a completed one at a glance
 * @param refundedAt        when the capture was reversed, null if it never was. {@code settledAt} is
 *                          left populated alongside it on purpose: a refunded payment is one that
 *                          was charged and then given back, and hiding the first half would make the
 *                          second unintelligible.
 */
public record PaymentResponse(
		UUID id,
		UUID orderId,
		String orderNumber,
		String customerId,
		BigDecimal amount,
		String currency,
		PaymentStatus status,
		String providerReference,
		String failureCode,
		String failureReason,
		Instant requestedAt,
		Instant settledAt,
		Instant refundedAt,
		String refundReference) {

	public static PaymentResponse from(Payment payment) {
		return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getOrderNumber(),
				payment.getCustomerId(), payment.getAmount(), payment.getCurrency(), payment.getStatus(),
				payment.getProviderReference(), payment.getFailureCode(), payment.getFailureReason(),
				payment.getRequestedAt(), payment.getSettledAt(), payment.getRefundedAt(),
				payment.getRefundReference());
	}
}
