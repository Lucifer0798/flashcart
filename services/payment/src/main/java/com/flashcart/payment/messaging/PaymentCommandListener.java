package com.flashcart.payment.messaging;

import java.util.UUID;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.RequestPayment;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.payment.service.PaymentService;
import org.slf4j.MDC;
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

	private final PaymentService payments;

	public PaymentCommandListener(PaymentService payments) {
		this.payments = payments;
	}

	@KafkaListener(topics = Topics.PAYMENT_COMMANDS, containerFactory = "requestPaymentFactory",
			groupId = PaymentKafkaConfig.GROUP)
	public void onRequestPayment(RequestPayment command) {
		if (command.correlationId() != null) {
			MDC.put(CorrelationId.MDC_KEY, command.correlationId());
		}
		try {
			payments.charge(
					UUID.fromString(command.aggregateId()),
					command.orderNumber(),
					command.customerId(),
					command.amount(),
					command.currency(),
					command.idempotencyKey());
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}
}
