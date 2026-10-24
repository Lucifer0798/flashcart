package com.flashcart.order.messaging;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.AccountClosed;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.order.service.ClosedAccounts;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Hears that an account was closed, so its leftover tokens stop being able to buy. See ADR 0053.
 *
 * <p>Apart from {@link OrderEventListener}, which drives the saga. This is not part of any order's story.
 */
@Component
public class AccountClosedListener {

	private static final String CONSUMER = "order-closed-accounts";

	private final IdempotentHandler handler;
	private final ClosedAccounts closed;

	public AccountClosedListener(IdempotentHandler handler, ClosedAccounts closed) {
		this.handler = handler;
		this.closed = closed;
	}

	@KafkaListener(topics = Topics.USER_EVENTS, containerFactory = "accountClosedFactory",
			groupId = OrderKafkaConfig.GROUP + "-account-closed")
	public void onAccountClosed(AccountClosed event) {
		handler.handle(event, CONSUMER, () -> closed.record(event.customerId(), event.occurredAt()));
	}
}
