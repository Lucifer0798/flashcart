package com.flashcart.payment.messaging;

import java.util.UUID;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.RefundPayment;
import com.flashcart.common.event.message.RequestPayment;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.payment.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Takes payment when the order service asks for it.
 *
 * <p>Note there is no HTTP endpoint that charges a card. Payment is only ever initiated by a command
 * on the bus, which means the one operation in this platform that moves money has exactly one entry
 * point — worth insisting on, because "how could this customer have been charged" should have a
 * short answer.
 */
@Component
public class PaymentCommandListener {

	/** Names this consumer in {@code processed_events}. */
	private static final String CONSUMER = "payment-commands";

	private final PaymentService payments;
	private final IdempotentHandler handler;

	public PaymentCommandListener(PaymentService payments, IdempotentHandler handler) {
		this.payments = payments;
		this.handler = handler;
	}

	@KafkaListener(topics = Topics.PAYMENT_COMMANDS, containerFactory = "requestPaymentFactory",
			groupId = PaymentKafkaConfig.GROUP)
	public void onRequestPayment(RequestPayment command) {
		// Two independent defences on the most expensive operation in the platform. The
		// processed-events claim stops a redelivered command reaching the provider at all, and
		// PaymentService remains separately idempotent on the same key in case one ever does.
		// Charging a customer twice is not a failure you get to apologise your way out of.
		handler.handle(command, CONSUMER, () -> payments.charge(
				UUID.fromString(command.aggregateId()),
				command.orderNumber(),
				command.customerId(),
				command.amount(),
				command.currency(),
				command.idempotencyKey()));
	}

	/**
	 * Its own consumer group, and that is not cosmetic.
	 *
	 * <p>Two listeners in one group on one topic are two members of that group: Kafka hands each of
	 * them a share of the partitions, and whichever one owns a partition filters out every message of
	 * the other's type and acknowledges it. The result is not a warning or a lag — it is both
	 * listeners silently missing most of their own messages. A group each means both read every
	 * partition and filter independently.
	 */
	@KafkaListener(topics = Topics.PAYMENT_COMMANDS, containerFactory = "refundPaymentFactory",
			groupId = PaymentKafkaConfig.GROUP + "-refund")
	public void onRefundPayment(RefundPayment command) {
		// The same two defences as the charge. A refund paid twice is cheaper than a charge taken
		// twice and no less wrong, and the claim is what stops the provider ever hearing it.
		handler.handle(command, CONSUMER, () -> payments.refund(
				command.orderNumber(), command.reason(), command.idempotencyKey()));
	}
}
