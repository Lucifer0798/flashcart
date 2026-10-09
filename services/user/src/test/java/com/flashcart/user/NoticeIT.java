package com.flashcart.user;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.message.BackInStock;
import com.flashcart.common.event.message.OrderCancelled;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.common.event.message.OrderDispatched;
import com.flashcart.common.event.message.PaymentRefunded;
import com.flashcart.user.messaging.NoticeListener;
import com.flashcart.user.notice.NoticeSender;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user service telling shoppers what they would want to know. See ADRs 0046 and 0048.
 *
 * <p>The mail server is a recording stand-in that can be told to refuse, because "the mail server is
 * down" is the case this design exists for and a real one cannot be switched off on demand. The real
 * SMTP path is exercised against Mailpit in the compose stack and in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class NoticeIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	/** Records what would have been sent, or refuses everything while {@link #down} is set. */
	static class RecordingMailSender extends JavaMailSenderImpl {

		final List<SimpleMailMessage> sent = new ArrayList<>();
		volatile boolean down;

		@Override
		public synchronized void send(SimpleMailMessage... messages) {
			if (down) {
				throw new MailSendException("Connection refused: mail server is down (test)");
			}
			sent.addAll(List.of(messages));
		}

		@Override
		public void send(MimeMessage... messages) {
			throw new UnsupportedOperationException("notices are plain text");
		}
	}

	@TestConfiguration
	static class Mail {

		@Bean
		@Primary
		RecordingMailSender recordingMailSender() {
			return new RecordingMailSender();
		}
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private NoticeListener listener;

	@Autowired
	private NoticeSender sender;

	@Autowired
	private RecordingMailSender mail;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MeterRegistry meters;

	@BeforeEach
	void reset() {
		mail.sent.clear();
		mail.down = false;
		// Each test reads the queue as a whole; leftovers from another would be sent by this one's call.
		jdbc.update("delete from notices");
	}

	// --- back in stock (ADR 0046) ------------------------------------------------------------------

	@Test
	@DisplayName("a shopper told by inventory gets one email, saying until when the unit is held")
	void toldMeansEmailed() {
		Shopper shopper = register();

		listener.onBackInStock(backInStock(UUID.randomUUID(), shopper.id(), Instant.parse("2099-01-01T12:34:00Z")));
		assertThat(sender.sendDue()).isEqualTo(1);

		assertThat(mail.sent).hasSize(1);
		SimpleMailMessage message = mail.sent.get(0);
		assertThat(message.getTo()).containsExactly(shopper.email());
		assertThat(message.getSubject()).isEqualTo("Back in stock: AUD-HP-001");
		assertThat(message.getText()).contains("holding one for you until 12:34 UTC");
	}

	@Test
	@DisplayName("the same place in the queue is emailed once, however many times it is announced")
	void oneEmailPerPlaceInTheQueue() {
		Shopper shopper = register();
		UUID entry = UUID.randomUUID();

		listener.onBackInStock(backInStock(entry, shopper.id(), future()));
		listener.onBackInStock(backInStock(entry, shopper.id(), future()));
		sender.sendDue();
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
	}

	@Test
	@DisplayName("a notice whose held unit has already gone to the next in line is not sent late")
	void lapsedHoldIsNotEmailed() {
		Shopper shopper = register();
		UUID entry = UUID.randomUUID();

		listener.onBackInStock(backInStock(entry, shopper.id(), Instant.now().minusSeconds(60)));
		sender.sendDue();

		assertThat(mail.sent).isEmpty();
		assertThat(row("back-in-stock:" + entry)).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "STALE");
	}

	@Test
	@DisplayName("a back-in-stock notice with no unit held still goes, and does not promise one")
	void noHoldStillTold() {
		Shopper shopper = register();

		listener.onBackInStock(backInStock(UUID.randomUUID(), shopper.id(), null));
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.get(0).getText()).doesNotContain("holding one for you");
	}

	// --- orders (ADR 0048) -------------------------------------------------------------------------

	@Test
	@DisplayName("a confirmed order is emailed once, however many times it is announced")
	void orderConfirmedOnce() {
		Shopper shopper = register();

		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-CONF0001", shopper.id()));
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-CONF0001", shopper.id()));
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.get(0).getSubject()).isEqualTo("Order FC-CONF0001 confirmed");
		assertThat(mail.sent.get(0).getTo()).containsExactly(shopper.email());
	}

	@Test
	@DisplayName("an order email past its shelf life is skipped, so a replayed topic does not mail its history")
	void oldNewsIsNotSent() {
		Shopper shopper = register();
		Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));

		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE, twoDaysAgo), "FC-OLDNEWS1", shopper.id()));
		sender.sendDue();

		assertThat(mail.sent).isEmpty();
		assertThat(row("order-confirmed:FC-OLDNEWS1")).containsEntry("skip_reason", "STALE");
	}

	@Test
	@DisplayName("a cancellation says why in the shopper's terms, and whether money comes back")
	void cancellationSaysWhy() {
		Shopper shopper = register();
		Map<String, String> expected = Map.of(
				"CANCELLED_AFTER_PAYMENT", "money is on its way back",
				"INSUFFICIENT_STOCK", "sold out before we could hold one",
				"CUSTOMER_LIMIT_EXCEEDED", "limited number per customer",
				"CARD_DECLINED", "payment was declined",
				"RESERVATION_EXPIRED", "did not complete in time",
				"SOMETHING_NEW", "You have not been charged");
		int n = 0;
		for (Map.Entry<String, String> reason : expected.entrySet()) {
			String order = "FC-CANC%04d".formatted(n++);
			listener.onOrderCancelled(new OrderCancelled(meta(OrderCancelled.TYPE), order, reason.getKey(), shopper.id()));
			sender.sendDue();
			SimpleMailMessage message = mail.sent.get(mail.sent.size() - 1);
			assertThat(message.getSubject()).isEqualTo("Order %s was cancelled".formatted(order));
			assertThat(message.getText()).as(reason.getKey()).contains(reason.getValue());
			// No code ever reaches the shopper as a code.
			assertThat(message.getText()).as(reason.getKey()).doesNotContain(reason.getKey());
		}
	}

	@Test
	@DisplayName("a refund says how much, once payment has actually moved it")
	void refundSaysHowMuch() {
		Shopper shopper = register();

		listener.onPaymentRefunded(new PaymentRefunded(meta("PaymentRefunded"), UUID.randomUUID().toString(),
				new BigDecimal("24.99"), "USD", "re_123", "FC-REFD0001", shopper.id()));
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.get(0).getSubject()).isEqualTo("Refund for order FC-REFD0001");
		assertThat(mail.sent.get(0).getText()).contains("refunded 24.99 USD");
	}

	@Test
	@DisplayName("an order's emails go out in the order things happened, even when the first is held up")
	void oneOrdersEmailsStayInOrder() {
		Shopper shopper = register();
		Instant confirmedAt = Instant.now().minusSeconds(60);

		// Confirmed first; the mail server refuses it, so it waits out a backoff.
		mail.down = true;
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE, confirmedAt), "FC-SEQ00001", shopper.id()));
		sender.sendDue();
		mail.down = false;

		// Dispatched arrives while confirmed is still backing off. It must not overtake it.
		listener.onOrderDispatched(new OrderDispatched(meta(OrderDispatched.TYPE), "FC-SEQ00001", shopper.id(), Instant.now()));
		sender.sendDue();
		assertThat(mail.sent).isEmpty();

		jdbc.update("update notices set next_attempt_at = now() - interval '1 second' where notice_key = 'order-confirmed:FC-SEQ00001'");
		sender.sendDue();

		assertThat(mail.sent).extracting(SimpleMailMessage::getSubject)
				.containsExactly("Order FC-SEQ00001 confirmed", "Order FC-SEQ00001 is on its way");
	}

	@Test
	@DisplayName("heard out of order, an order's emails still go out in the order things happened")
	void sequenceIsWhenItHappenedNotWhenItWasHeard() {
		Shopper shopper = register();
		Instant now = Instant.now();

		// Separate consumer groups: the dispatch is heard before the confirmation it follows.
		listener.onOrderDispatched(new OrderDispatched(meta(OrderDispatched.TYPE, now), "FC-OOO00001", shopper.id(), now));
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE, now.minusSeconds(30)), "FC-OOO00001",
				shopper.id()));
		sender.sendDue();

		assertThat(mail.sent).extracting(SimpleMailMessage::getSubject)
				.containsExactly("Order FC-OOO00001 confirmed", "Order FC-OOO00001 is on its way");
	}

	@Test
	@DisplayName("another order's held-up email does not hold this one back")
	void orderingIsPerOrder() {
		Shopper shopper = register();
		mail.down = true;
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-SEQA0001", shopper.id()));
		sender.sendDue();
		mail.down = false;

		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-SEQB0001", shopper.id()));
		sender.sendDue();

		assertThat(mail.sent).extracting(SimpleMailMessage::getSubject).containsExactly("Order FC-SEQB0001 confirmed");
	}

	// --- sending ----------------------------------------------------------------------------------

	@Test
	@DisplayName("a mail server that is down delays the notice; it is sent when the server is back")
	void mailDownIsADelayNotALoss() {
		Shopper shopper = register();
		UUID entry = UUID.randomUUID();
		listener.onBackInStock(backInStock(entry, shopper.id(), future()));

		mail.down = true;
		sender.sendDue();

		Map<String, Object> row = row("back-in-stock:" + entry);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("attempts")).isEqualTo(1);
		assertThat((String) row.get("last_error")).contains("mail server is down");
		// Not retried at once: the next attempt waits, so an outage is not hammered every two seconds.
		assertThat(sender.sendDue()).isZero();
		assertThat(mail.sent).isEmpty();

		mail.down = false;
		jdbc.update("update notices set next_attempt_at = now() where notice_key = ?", "back-in-stock:" + entry);
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(row("back-in-stock:" + entry).get("status")).isEqualTo("SENT");
	}

	@Test
	@DisplayName("one refusal ends the batch: the rest wait for the next tick, not each for its own timeout")
	void refusalStopsTheBatch() {
		Shopper shopper = register();
		for (int i = 0; i < 3; i++) {
			listener.onBackInStock(backInStock(UUID.randomUUID(), shopper.id(), future()));
		}

		mail.down = true;
		assertThat(sender.sendDue()).isEqualTo(1);

		assertThat(jdbc.queryForList("select attempts from notices order by attempts", Integer.class))
				.containsExactly(0, 0, 1);
	}

	@Test
	@DisplayName("a mail outage does not make the user service unhealthy")
	void mailOutageIsNotAServiceOutage() {
		Map<?, ?> health = rest.getForObject("/actuator/health", Map.class);

		assertThat(health.get("status")).isEqualTo("UP");
	}

	@Test
	@DisplayName("a customer this service does not know is skipped, not retried for ever")
	void unknownCustomerIsSkipped() {
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-NOBODY01", "not-a-user"));
		// An OrderCancelled published before ADR 0048 carries no customer at all.
		listener.onOrderCancelled(new OrderCancelled(meta(OrderCancelled.TYPE), "FC-NOBODY02", "CARD_DECLINED", null));
		sender.sendDue();

		assertThat(mail.sent).isEmpty();
		assertThat(row("order-confirmed:FC-NOBODY01")).containsEntry("skip_reason", "NO_RECIPIENT");
		assertThat(row("order-cancelled:FC-NOBODY02")).containsEntry("skip_reason", "NO_RECIPIENT");
	}

	@Test
	@DisplayName("the backlog gauge says how far behind sending is, and returns to zero when caught up")
	void backlogIsMeasured() {
		Shopper shopper = register();
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-GAUGE001", shopper.id()));
		jdbc.update("update notices set created_at = now() - interval '15 minutes'");

		sender.refreshGauge();
		assertThat(gauge()).isGreaterThanOrEqualTo(15 * 60);

		sender.sendDue();
		sender.refreshGauge();
		assertThat(gauge()).isZero();
	}

	@Test
	@DisplayName("retries back off from thirty seconds to a ceiling of thirty minutes")
	void backoff() {
		assertThat(NoticeSender.backoff(1)).isEqualTo(Duration.ofSeconds(30));
		assertThat(NoticeSender.backoff(2)).isEqualTo(Duration.ofMinutes(1));
		assertThat(NoticeSender.backoff(4)).isEqualTo(Duration.ofMinutes(4));
		assertThat(NoticeSender.backoff(7)).isEqualTo(Duration.ofMinutes(30));
		assertThat(NoticeSender.backoff(50)).isEqualTo(Duration.ofMinutes(30));
	}

	// --- helpers ------------------------------------------------------------------------------------

	private record Shopper(String id, String email) {
	}

	@SuppressWarnings("unchecked")
	private Shopper register() {
		String email = "shopper-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		Map<String, Object> created = rest.postForObject("/api/v1/users",
				Map.of("email", email, "password", "a-sufficiently-long-password", "displayName", "Patient Shopper"),
				Map.class);
		return new Shopper((String) created.get("id"), email);
	}

	private static EventMetadata meta(String type) {
		return EventMetadata.of(type, UUID.randomUUID());
	}

	/** Metadata with a chosen occurredAt, for the tests about sequence. */
	private static EventMetadata meta(String type, Instant occurredAt) {
		EventMetadata now = EventMetadata.of(type, UUID.randomUUID());
		return new EventMetadata(now.eventId(), now.eventType(), now.aggregateId(), occurredAt, now.correlationId());
	}

	private static BackInStock backInStock(UUID entry, String customerId, Instant heldUntil) {
		return new BackInStock(EventMetadata.of(BackInStock.TYPE, entry), entry.toString(), "AUD-HP-001", customerId,
				Instant.now(), heldUntil);
	}

	private static Instant future() {
		return Instant.now().plus(Duration.ofMinutes(10));
	}

	private Map<String, Object> row(String key) {
		return jdbc.queryForMap("select * from notices where notice_key = ?", key);
	}

	private double gauge() {
		return meters.get("flashcart.user.notices.oldest.pending.seconds").gauge().value();
	}
}
