package com.flashcart.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.payment.config.PaymentProperties;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tries again to give back money the provider would not return the first time.
 *
 * <p>ADR 0030 left this open in as many words: {@code REFUND_FAILED} was reachable, alerted on, and
 * had no way out. The order was already {@code CANCELLED}, so every other part of the platform
 * believed the customer had been made whole, and the only thing that disagreed was a row nobody was
 * going to look at until somebody complained.
 *
 * <h2>Bounded, and that is the design</h2>
 *
 * Most refusals are permanent — a capture too old to reverse, an account that has since closed — and
 * a job that retried those forever would produce an alert that is always firing, which is an alert
 * nobody reads. So it gives up after {@code maxAttempts} refusals and says so, loudly and once.
 *
 * <p>Giving up on retrying is <strong>not</strong> deciding the money is not owed. The payment stays
 * {@code REFUND_FAILED} and stays refundable, so a later command still works; what stops is this
 * job's automatic attempts. The distinction is why the cap lives here and not in
 * {@link PaymentStatus#isRefundable()} — the domain's answer to "can this be refunded" should not
 * depend on how many times a scheduled job has already tried.
 *
 * <h2>Why there is no endpoint to press instead</h2>
 *
 * The obvious alternative is a button for an operator. It was rejected because payment deliberately
 * has no HTTP endpoint that moves money — "how could this customer have been charged" has a
 * one-word answer, and adding a way to send money out by request would spend that property on a
 * convenience. This job reaches {@code PaymentService.refund} by the same path a command does.
 */
@Service
public class RefundRetryService {

	private static final Logger log = LoggerFactory.getLogger(RefundRetryService.class);

	private final PaymentRepository payments;
	private final PaymentService refunds;
	private final PaymentProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;
	private final Counter abandoned;

	public RefundRetryService(PaymentRepository payments, PaymentService refunds,
			PaymentProperties properties, Clock clock, PlatformTransactionManager transactionManager,
			MeterRegistry meters) {
		this.payments = payments;
		this.refunds = refunds;
		this.properties = properties;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
		this.abandoned = Counter.builder("flashcart.payment.refunds.abandoned")
				.description("Refunds the platform has stopped attempting; the money is still owed")
				.register(meters);
	}

	@Scheduled(
			fixedDelayString = "${flashcart.payment.refund-retry.fixed-delay:PT60S}",
			initialDelayString = "${flashcart.payment.refund-retry.initial-delay:PT30S}")
	public void retry() {
		if (!properties.refundRetry().enabled()) {
			return;
		}
		try {
			int attempted = retryBatch();
			if (attempted > 0) {
				log.info("Re-attempted {} refused refund(s)", attempted);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is not rescheduled by some executors, and this one
			// stopping means money quietly stays where it should not be.
			log.error("Refund retry failed; will try again on the next tick", ex);
		}
	}

	/** One batch, exposed so tests can drive it deterministically instead of waiting on a timer. */
	public int retryBatch() {
		PaymentProperties.RefundRetry settings = properties.refundRetry();
		Instant cutoff = clock.instant().minus(settings.delay());

		List<UUID> candidates = transactions.execute(status -> payments.claimRetryableRefunds(
				cutoff, settings.maxAttempts(), settings.batchSize()));

		int attempted = 0;
		for (UUID paymentId : candidates) {
			if (attempt(paymentId, settings.maxAttempts())) {
				attempted++;
			}
		}
		return attempted;
	}

	private boolean attempt(UUID paymentId, int maxAttempts) {
		Payment payment = transactions.execute(status -> payments.findById(paymentId).orElse(null));
		// Re-checked rather than trusted: between the claim query and here the money may already
		// have gone back, which is the outcome this job exists to reach.
		if (payment == null || payment.getStatus() != PaymentStatus.REFUND_FAILED) {
			return false;
		}

		Payment after = refunds.refund(payment.getOrderNumber(), "refund retry",
				"refund:" + payment.getOrderId());

		if (after.getStatus() == PaymentStatus.REFUNDED) {
			log.info("Refund for order {} succeeded on attempt {}", payment.getOrderNumber(),
					payment.getRefundAttempts() + 1);
			return true;
		}

		// Counted at the moment the cap is reached rather than every time a capped payment is seen,
		// which is why the claim query excludes them: this counter has to mean "the platform gave up
		// on another one", not "the platform is still holding some".
		if (after.getRefundAttempts() >= maxAttempts) {
			abandoned.increment();
			log.error("Giving up on refunding order {} after {} refusals; {} {} is still owed to {}. "
							+ "It remains refundable -- this job will simply stop trying.",
					payment.getOrderNumber(), after.getRefundAttempts(), after.getAmount(),
					after.getCurrency(), after.getCustomerId());
		}
		return true;
	}
}
