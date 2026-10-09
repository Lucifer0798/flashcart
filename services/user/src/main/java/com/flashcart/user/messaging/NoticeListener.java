package com.flashcart.user.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.BackInStock;
import com.flashcart.common.event.message.OrderCancelled;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.common.event.message.OrderDelivered;
import com.flashcart.common.event.message.OrderDispatched;
import com.flashcart.common.event.message.PaymentRefunded;
import com.flashcart.common.event.outbox.IdempotentHandler;
import com.flashcart.user.notice.NoticeKind;
import com.flashcart.user.notice.Notices;
import com.flashcart.user.notice.Notices.Notice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns the events a shopper would want to hear about into emails to send.
 *
 * <p>Records, and does not send: the insert is quick and local, and {@code NoticeSender} does the
 * waiting on a mail server, for as long as it takes. Each email is keyed by what it is about -- one
 * per order per kind, one per place in a waitlist -- so a repeat announcement is dropped rather than
 * sent twice. See ADRs 0046 and 0048.
 *
 * <p>Every email has a shelf life: {@code flashcart.notices.max-age} after the event, it is skipped as
 * stale rather than sent. Consumers start from the earliest offset -- right for the saga, which must
 * not miss a command -- so the first deploy of this listener read the order topic from the beginning
 * and emailed shoppers that their days-old orders were confirmed. An email a day late is noise; one
 * replayed from history is worse. The back-in-stock email keeps its own, shorter limit: the hold.
 *
 * <p>Order emails come from the order service's own events, not shipping's or inventory's: the order
 * is the authority on its story. The refund is the exception, because the money is payment's, and an
 * order cancelled after payment is not refunded until payment says so.
 */
@Component
public class NoticeListener {

	private static final String CONSUMER = "user-notices";

	private final IdempotentHandler handler;
	private final Notices notices;
	private final Duration maxAge;

	public NoticeListener(IdempotentHandler handler, Notices notices,
			@Value("${flashcart.notices.max-age:P1D}") Duration maxAge) {
		this.handler = handler;
		this.notices = notices;
		this.maxAge = maxAge;
	}

	@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "backInStockFactory",
			groupId = UserKafkaConfig.GROUP + "-back-in-stock")
	public void onBackInStock(BackInStock event) {
		Instant sendBefore = event.heldUntil() != null ? event.heldUntil() : event.occurredAt().plus(maxAge);
		record(event, new Notice("back-in-stock:" + event.waitlistEntryId(), NoticeKind.BACK_IN_STOCK,
				event.customerId(), null, event.occurredAt(), backInStockPayload(event), sendBefore));
	}

	@KafkaListener(topics = Topics.ORDER_EVENTS, containerFactory = "orderConfirmedFactory",
			groupId = UserKafkaConfig.GROUP + "-order-confirmed")
	public void onOrderConfirmed(OrderConfirmed event) {
		record(event, order(event, NoticeKind.ORDER_CONFIRMED, "order-confirmed", event.orderNumber(),
				event.customerId(), Map.of()));
	}

	@KafkaListener(topics = Topics.ORDER_EVENTS, containerFactory = "orderDispatchedFactory",
			groupId = UserKafkaConfig.GROUP + "-order-dispatched")
	public void onOrderDispatched(OrderDispatched event) {
		record(event, order(event, NoticeKind.ORDER_DISPATCHED, "order-dispatched", event.orderNumber(),
				event.customerId(), Map.of()));
	}

	@KafkaListener(topics = Topics.ORDER_EVENTS, containerFactory = "orderDeliveredFactory",
			groupId = UserKafkaConfig.GROUP + "-order-delivered")
	public void onOrderDelivered(OrderDelivered event) {
		record(event, order(event, NoticeKind.ORDER_DELIVERED, "order-delivered", event.orderNumber(),
				event.customerId(), Map.of()));
	}

	@KafkaListener(topics = Topics.ORDER_EVENTS, containerFactory = "orderCancelledFactory",
			groupId = UserKafkaConfig.GROUP + "-order-cancelled")
	public void onOrderCancelled(OrderCancelled event) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("reason", event.reason());
		record(event, order(event, NoticeKind.ORDER_CANCELLED, "order-cancelled", event.orderNumber(),
				event.customerId(), payload));
	}

	@KafkaListener(topics = Topics.PAYMENT_EVENTS, containerFactory = "paymentRefundedFactory",
			groupId = UserKafkaConfig.GROUP + "-payment-refunded")
	public void onPaymentRefunded(PaymentRefunded event) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("amount", event.amount() == null ? null : event.amount().toPlainString());
		payload.put("currency", event.currency());
		record(event, order(event, NoticeKind.REFUNDED, "refunded", event.orderNumber(), event.customerId(),
				payload));
	}

	private static Map<String, Object> backInStockPayload(BackInStock event) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("sku", event.sku());
		if (event.heldUntil() != null) {
			payload.put("heldUntil", event.heldUntil().toString());
		}
		return payload;
	}

	private Notice order(DomainEvent event, NoticeKind kind, String prefix, String orderNumber,
			String customerId, Map<String, Object> payload) {
		return new Notice(prefix + ":" + orderNumber, kind, customerId, orderNumber, event.occurredAt(), payload,
				event.occurredAt().plus(maxAge));
	}

	private void record(DomainEvent event, Notice notice) {
		handler.handle(event, CONSUMER, () -> notices.receive(notice));
	}
}
