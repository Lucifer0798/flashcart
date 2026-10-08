package com.flashcart.user.messaging;

import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.BackInStock;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.user.notice.BackInStockNotices;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Receives inventory's decision that a shopper should be told, and records it for sending.
 *
 * <p>Records, and does not send. Sending waits on a mail server; a handler that waited on one would
 * spend the consumer's few seconds of retries on an outage and then dead-letter the notice. The
 * insert is quick and local, and {@link com.flashcart.user.notice.BackInStockSender} does the waiting,
 * for as long as it takes. See ADR 0046.
 */
@Component
public class BackInStockListener {

	private static final String CONSUMER = "user-back-in-stock";

	private final IdempotentHandler handler;
	private final BackInStockNotices notices;

	public BackInStockListener(IdempotentHandler handler, BackInStockNotices notices) {
		this.handler = handler;
		this.notices = notices;
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "backInStockFactory",
			groupId = UserKafkaConfig.GROUP + "-back-in-stock")
	public void onBackInStock(BackInStock event) {
		handler.handle(event, CONSUMER, () -> notices.receive(event));
	}
}
