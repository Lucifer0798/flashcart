package com.flashcart.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.PaymentCompleted;
import com.flashcart.common.event.message.PaymentFailed;
import com.flashcart.common.event.message.PaymentTimedOut;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.domain.PaymentStatus;
import com.flashcart.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes money, and is careful about the case where it cannot tell whether it did.
 *
 * <h2>The order of operations is the whole design</h2>
 *
 * The attempt is persisted as {@code PENDING} <em>before</em> the provider is called, and updated
 * after. That ordering is not incidental: if the process dies mid-charge, a record of the attempt
 * exists and reconciliation can go and ask the provider what became of it. Calling first and
 * persisting after would leave a charge nobody in this system has ever heard of.
 *
 * <p>Like the order service, no transaction spans the provider call. A database connection held open
 * across a network call to a third party is how one slow dependency exhausts a pool.
 */
@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final PaymentRepository payments;
	private final PaymentProvider provider;
	private final EventPublisher events;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public PaymentService(PaymentRepository payments, PaymentProvider provider, EventPublisher events,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.payments = payments;
		this.provider = provider;
		this.events = events;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Charge for an order, then publish what happened.
	 *
	 * <p>Idempotent on {@code idempotencyKey} — the order id. A redelivered command finds the
	 * existing attempt and re-publishes its outcome rather than charging again, which matters more
	 * here than anywhere else in the platform: a customer charged twice is the most expensive
	 * possible consequence of at-least-once delivery.
	 */
	public Payment charge(UUID orderId, String orderNumber, String customerId, BigDecimal amount,
			String currency, String idempotencyKey) {

		Payment existing = payments.findByIdempotencyKey(idempotencyKey).orElse(null);
		if (existing != null) {
			log.info("Payment for {} already exists as {}; re-publishing its outcome", idempotencyKey,
					existing.getStatus());
			// Re-published rather than ignored: the duplicate command usually means the *first*
			// outcome event was the thing that got lost, so silence here would strand the order.
			republish(existing);
			return existing;
		}

		Payment payment;
		try {
			payment = transactions.execute(status -> payments.saveAndFlush(
					new Payment(UUID.randomUUID(), orderId, orderNumber, customerId, amount, currency,
							idempotencyKey, clock.instant())));
		}
		catch (DataIntegrityViolationException ex) {
			// Two commands with the same key arrived at once. The unique constraint is the real
			// defence; the loser republishes the winner's outcome.
			Payment winner = payments.findByIdempotencyKey(idempotencyKey).orElseThrow();
			republish(winner);
			return winner;
		}

		try {
			PaymentProvider.Outcome outcome =
					provider.charge(idempotencyKey, amount, currency, customerId);

			if (outcome.approved()) {
				return settle(payment.getId(), settled -> {
					settled.complete(outcome.providerReference(), clock.instant());
					events.publish(Topics.PAYMENT_EVENTS, new PaymentCompleted(
							EventMetadata.of(PaymentCompleted.TYPE, orderId),
							settled.getId().toString(), amount, currency, outcome.providerReference()));
				});
			}
			return settle(payment.getId(), settled -> {
				settled.fail(outcome.declineCode(), outcome.declineReason(), clock.instant());
				events.publish(Topics.PAYMENT_EVENTS, new PaymentFailed(
						EventMetadata.of(PaymentFailed.TYPE, orderId),
						settled.getId().toString(), outcome.declineCode(), outcome.declineReason()));
			});
		}
		catch (PaymentProvider.ProviderTimeoutException ex) {
			// Nobody knows whether the money moved. Publishing PaymentTimedOut rather than
			// PaymentFailed is the whole point: the order saga must not release stock on this,
			// because the charge may still land and it would then owe a refund on a unit it had
			// already sold to somebody else.
			log.warn("Payment provider timed out for {}", idempotencyKey);
			return settle(payment.getId(), settled -> {
				settled.timeOut(ex.getMessage());
				events.publish(Topics.PAYMENT_EVENTS, new PaymentTimedOut(
						EventMetadata.of(PaymentTimedOut.TYPE, orderId), settled.getId().toString()));
			});
		}
	}

	@Transactional(readOnly = true)
	public Payment get(UUID paymentId) {
		return payments.findById(paymentId)
				.orElseThrow(() -> ResourceNotFoundException.of("Payment", paymentId));
	}

	@Transactional(readOnly = true)
	public Payment getByOrderNumber(String orderNumber) {
		return payments.findByOrderNumber(orderNumber)
				.orElseThrow(() -> ResourceNotFoundException.of("Payment for order", orderNumber));
	}

	@Transactional(readOnly = true)
	public List<Payment> forCustomer(String customerId) {
		return payments.findByCustomerIdOrderByCreatedAtDesc(customerId);
	}

	private Payment settle(UUID paymentId, java.util.function.Consumer<Payment> apply) {
		return transactions.execute(status -> {
			Payment payment = payments.findById(paymentId).orElseThrow();
			apply.accept(payment);
			return payment;
		});
	}

	private void republish(Payment payment) {
		switch (payment.getStatus()) {
			case COMPLETED -> events.publish(Topics.PAYMENT_EVENTS, new PaymentCompleted(
					EventMetadata.of(PaymentCompleted.TYPE, payment.getOrderId()),
					payment.getId().toString(), payment.getAmount(), payment.getCurrency(),
					payment.getProviderReference()));
			case FAILED -> events.publish(Topics.PAYMENT_EVENTS, new PaymentFailed(
					EventMetadata.of(PaymentFailed.TYPE, payment.getOrderId()),
					payment.getId().toString(), payment.getFailureCode(), payment.getFailureReason()));
			case TIMED_OUT -> events.publish(Topics.PAYMENT_EVENTS, new PaymentTimedOut(
					EventMetadata.of(PaymentTimedOut.TYPE, payment.getOrderId()),
					payment.getId().toString()));
			// Still in flight from the first command. Saying anything now would be a guess.
			case PENDING -> log.debug("Payment {} is still pending; nothing to republish",
					payment.getId());
		}
	}

	/** Exposed for the reconciler. */
	PaymentRepository repository() {
		return payments;
	}

	PaymentStatus statusOf(UUID paymentId) {
		return get(paymentId).getStatus();
	}
}
