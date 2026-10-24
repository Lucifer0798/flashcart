package com.flashcart.inventory.messaging;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.AccountClosed;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.inventory.service.AccountClosure;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Takes a closed account out of every waitlist. See ADR 0053. */
@Component
public class AccountClosedListener {

	private static final String CONSUMER = "inventory-closed-accounts";

	private final IdempotentHandler handler;
	private final AccountClosure closure;

	public AccountClosedListener(IdempotentHandler handler, AccountClosure closure) {
		this.handler = handler;
		this.closure = closure;
	}

	@KafkaListener(topics = Topics.USER_EVENTS, containerFactory = "accountClosedFactory",
			groupId = InventoryKafkaConfig.GROUP + "-account-closed")
	public void onAccountClosed(AccountClosed event) {
		handler.handle(event, CONSUMER, () -> closure.forget(event.customerId()));
	}
}
