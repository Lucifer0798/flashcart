package com.flashcart.payment.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.PaymentTimedOut;
import com.flashcart.payment.config.PaymentProperties;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves attempts the provider never answered.
 *
 * <p>An attempt goes {@code PENDING} before the provider is called and is updated after. If this
 * process dies in between — or the provider simply never replies and the call is abandoned — the row
 * stays {@code PENDING} forever, and the order behind it stays in {@code PAYMENT_PENDING} forever
 * with its stock held. Somebody has to notice.
 *
 * <p>What it does <em>not</em> do is decide the money did not move. It marks the attempt
 * {@code TIMED_OUT} and publishes {@link PaymentTimedOut}, which sends the order to
 * {@code PAYMENT_TIMEOUT} — a state that deliberately does not release inventory. Resolving what
 * actually happened needs the provider's own record, which in a real system means querying it by the
 * idempotency key. That query is the obvious next step and is not simulated here, because a fake
 * answer would make this look solved when it is not.
 */
@Service
public class PaymentReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

	private final PaymentRepository payments;
	private final EventPublisher events;
	private final PaymentProperties properties;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public PaymentReconciliationService(PaymentRepository payments, EventPublisher events,
			PaymentProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
		this.payments = payments;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Scheduled(
			fixedDelayString = "${flashcart.payment.reconciler.fixed-delay:PT30S}",
			initialDelayString = "${flashcart.payment.reconciler.initial-delay:PT25S}")
	public void reconcile() {
		if (!properties.reconciler().enabled()) {
			return;
		}
		try {
			int resolved = reconcileBatch();
			if (resolved > 0) {
				log.warn("Marked {} payment attempt(s) as timed out; they need provider reconciliation",
						resolved);
			}
		}
		catch (RuntimeException ex) {
			log.error("Payment reconciliation failed; will retry on the next tick", ex);
		}
	}

	/** One batch, exposed so tests can drive it without waiting on a timer. */
	public int reconcileBatch() {
		java.time.Instant cutoff = clock.instant().minus(properties.pendingTimeout());
		List<UUID> stale = transactions.execute(status ->
				payments.claimStalePending(cutoff, properties.reconciler().batchSize()));

		int resolved = 0;
		for (UUID paymentId : stale) {
			if (timeOut(paymentId)) {
				resolved++;
			}
		}
		return resolved;
	}

	private boolean timeOut(UUID paymentId) {
		Payment payment = transactions.execute(status -> {
			Payment found = payments.findById(paymentId).orElse(null);
			// Re-checked: the provider's answer may have arrived between the claim and here.
			if (found == null || found.getStatus() != PaymentStatus.PENDING) {
				return null;
			}
			found.timeOut("No response from the provider within " + properties.pendingTimeout());
			return found;
		});

		if (payment == null) {
			return false;
		}

		events.publish(Topics.PAYMENT_EVENTS, new PaymentTimedOut(
				EventMetadata.of(PaymentTimedOut.TYPE, payment.getOrderId()),
				payment.getId().toString()));
		return true;
	}
}
