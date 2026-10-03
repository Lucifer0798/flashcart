package com.flashcart.payment.service;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.payment.config.PaymentProperties;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds the platform has given up on: how many are still owed, and recording one as paid.
 *
 * <h2>Why the alert needed this</h2>
 *
 * ADR 0031's {@code RefundAbandoned} fired on {@code increase(abandoned_total[30m]) > 0} — the moment
 * of giving up. Thirty minutes later it resolved, whether or not anybody had paid the customer. An
 * alert that clears on its own while the money is still owed looks, on the dashboard, exactly like
 * one that cleared because somebody dealt with it.
 *
 * <p>{@code flashcart_payment_refunds_abandoned_outstanding} is the number of rows in that state
 * <em>now</em>. The alert fires while it is above zero, so it resolves when — and only when — every
 * abandoned refund has been settled.
 *
 * <h2>Recording one, without moving money</h2>
 *
 * ADR 0031 kept payment free of any HTTP endpoint that moves money, and this does not add one.
 * {@link #settleOutside} records that the customer was refunded somewhere else — the provider's own
 * console, a bank transfer — with the reference from wherever that was. Nothing is sent to the
 * provider. See ADR 0041.
 */
@Service
public class AbandonedRefunds {

	private static final Logger log = LoggerFactory.getLogger(AbandonedRefunds.class);

	private final PaymentRepository payments;
	private final PaymentProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;
	private final AtomicLong outstanding = new AtomicLong();
	private final Counter settledOutside;

	public AbandonedRefunds(PaymentRepository payments, PaymentProperties properties, Clock clock,
			PlatformTransactionManager transactionManager, MeterRegistry meters) {
		this.payments = payments;
		this.properties = properties;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
		// Backed by a number this service refreshes, not by a query run on every scrape: a scrape that
		// waits on the database is a scrape that fails when the database is the problem.
		Gauge.builder("flashcart.payment.refunds.abandoned.outstanding", outstanding, AtomicLong::get)
				.description("Refunds the retry job gave up on and nobody has recorded as paid; money still owed")
				.register(meters);
		this.settledOutside = Counter.builder("flashcart.payment.refunds.settled.outside")
				.description("Abandoned refunds recorded as paid outside the platform")
				.register(meters);
	}

	/**
	 * Re-count from the database. Independent of whether the retry job is enabled: the number of
	 * customers owed money is true whether or not anything is trying to pay them.
	 */
	@Scheduled(
			fixedDelayString = "${flashcart.payment.abandoned-refunds.refresh:PT60S}",
			initialDelayString = "${flashcart.payment.abandoned-refunds.initial-delay:PT5S}")
	public void refresh() {
		try {
			outstanding.set(count());
		}
		catch (RuntimeException ex) {
			// Keep the last value rather than dropping to zero. A gauge that reads zero because the
			// count failed would resolve the very alert that most needs to stay firing.
			log.warn("Could not count abandoned refunds; keeping the last value of {}", outstanding.get(), ex);
		}
	}

	/** Exposed so tests can read the count without waiting on the refresh. */
	public long count() {
		return payments.countAbandonedRefunds(properties.refundRetry().maxAttempts());
	}

	/**
	 * Record that an abandoned refund was paid outside the platform.
	 *
	 * <p>Idempotent for the same reference, so a retried request returns the settled payment rather
	 * than an error. A <em>different</em> reference for a payment already settled is refused: two
	 * references for one refund is either a typo or a customer paid twice, and neither should be
	 * quietly overwritten.
	 *
	 * @param operatorId the token subject of whoever is recording it
	 * @throws ResourceNotFoundException when no payment exists for the order
	 * @throws ConflictException         {@code REFUND_NOT_ABANDONED} while the retry job may still
	 *                                   ask the provider, {@code REFUND_NOT_OWED} when no refund is
	 *                                   outstanding, {@code REFUND_ALREADY_SETTLED} for a second
	 *                                   reference
	 */
	public Payment settleOutside(String orderNumber, String reference, String operatorId) {
		int maxAttempts = properties.refundRetry().maxAttempts();
		String trimmed = reference.trim();

		Integer updated = transactions.execute(status -> payments.settleAbandonedRefund(
				orderNumber, trimmed, operatorId, clock.instant(), maxAttempts));

		Payment payment = transactions.execute(status -> payments.findByOrderNumber(orderNumber).orElse(null));
		if (payment == null) {
			throw ResourceNotFoundException.of("Payment for order", orderNumber);
		}

		if (updated != null && updated == 1) {
			settledOutside.increment();
			refresh();
			log.warn("Order {}: {} {} owed to {} recorded as refunded outside the platform by {} "
							+ "(reference {})", orderNumber, payment.getAmount(), payment.getCurrency(),
					payment.getCustomerId(), operatorId, trimmed);
			return payment;
		}

		// Nothing was written. Say why, from the row as it now stands.
		if (payment.getStatus() == PaymentStatus.REFUNDED_OUTSIDE) {
			if (trimmed.equals(payment.getRefundReference())) {
				return payment;
			}
			throw new ConflictException("REFUND_ALREADY_SETTLED",
					"Order %s was already recorded as refunded outside the platform under reference %s"
							.formatted(orderNumber, payment.getRefundReference()));
		}
		if (payment.getStatus() == PaymentStatus.REFUND_FAILED) {
			throw new ConflictException("REFUND_NOT_ABANDONED",
					("Order %s has been refused %d of %d times and will be tried again automatically. "
							+ "Recording a refund made elsewhere now could pay the customer twice.")
							.formatted(orderNumber, payment.getRefundAttempts(), maxAttempts));
		}
		throw new ConflictException("REFUND_NOT_OWED",
				"Order %s is %s, so no refund is outstanding".formatted(orderNumber, payment.getStatus()));
	}
}
