package com.flashcart.user;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import com.flashcart.common.event.message.RefundSettledOutside;
import com.flashcart.common.error.OperatorRequiredException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.api.SupportController;
import com.flashcart.user.messaging.NoticeListener;
import com.flashcart.user.notice.NoticeRetention;
import com.flashcart.user.notice.NoticeSender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@Autowired
	private AccessTokens tokens;

	@Autowired
	private SupportController support;

	@Autowired
	private NoticeRetention retention;

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
				"CANCELLED_BY_CUSTOMER", "You cancelled it before paying",
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
	@DisplayName("a refund paid outside the platform says so, and gives the reference the bank will show")
	void refundSettledOutsideSaysHow() {
		Shopper shopper = register();

		listener.onRefundSettledOutside(new RefundSettledOutside(meta(RefundSettledOutside.TYPE),
				UUID.randomUUID().toString(), "FC-OUTS0001", shopper.id(), new BigDecimal("100.77"), "USD", "BANK-TX-0042"));
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.get(0).getSubject()).isEqualTo("Refund for order FC-OUTS0001");
		assertThat(mail.sent.get(0).getText()).contains("refunded 100.77 USD")
				.contains("sent to you directly").contains("BANK-TX-0042");
	}

	@Test
	@DisplayName("an order gets one refund email, whichever way the money went back")
	void oneRefundEmailPerOrder() {
		Shopper shopper = register();

		listener.onPaymentRefunded(new PaymentRefunded(meta("PaymentRefunded"), UUID.randomUUID().toString(),
				new BigDecimal("24.99"), "USD", "re_1", "FC-ONCE0001", shopper.id()));
		listener.onRefundSettledOutside(new RefundSettledOutside(meta(RefundSettledOutside.TYPE),
				UUID.randomUUID().toString(), "FC-ONCE0001", shopper.id(), new BigDecimal("24.99"), "USD", "BANK-TX-9"));
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
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

	// --- support reads (ADR 0051) ------------------------------------------------------------------

	@Test
	@DisplayName("support sees every email a customer was meant to get: sent, still trying and why, or skipped and why")
	void supportSeesWhatWasSentAndWhy() {
		Shopper shopper = register();
		Instant now = Instant.now();

		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE, now.minus(Duration.ofMinutes(5))),
				"FC-SUPP0001", shopper.id()));
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE, now.minus(Duration.ofDays(2))),
				"FC-SUPP0002", shopper.id()));
		sender.sendDue();
		mail.down = true;
		listener.onRefundSettledOutside(new RefundSettledOutside(meta(RefundSettledOutside.TYPE, now),
				UUID.randomUUID().toString(), "FC-SUPP0001", shopper.id(), new BigDecimal("100.77"), "USD", "BANK-TX-7"));
		sender.sendDue();

		List<Map<String, Object>> seen = notices("email=" + shopper.email().toUpperCase(), support());

		// Newest first. The email support is asked about is usually the most recent one.
		assertThat(seen).extracting(n -> n.get("kind"))
				.containsExactly("REFUNDED", "ORDER_CONFIRMED", "ORDER_CONFIRMED");

		Map<String, Object> refund = seen.get(0);
		assertThat(refund).containsEntry("status", "PENDING").containsEntry("subject", "Refund for order FC-SUPP0001")
				.containsEntry("attempts", 1);
		assertThat((String) refund.get("body")).contains("BANK-TX-7");
		assertThat((String) refund.get("lastError")).contains("mail server is down");
		assertThat(refund.get("nextAttemptAt")).isNotNull();

		Map<String, Object> sent = seen.get(1);
		assertThat(sent).containsEntry("status", "SENT").containsEntry("orderNumber", "FC-SUPP0001");
		assertThat(sent.get("sentAt")).isNotNull();
		assertThat(sent.get("nextAttemptAt")).isNull();

		assertThat(seen.get(2)).containsEntry("status", "SKIPPED").containsEntry("skipReason", "STALE");
		assertThat(seen.get(2).get("sentAt")).isNull();
	}

	@Test
	@DisplayName("support may ask by id, narrow to one order, and never sees another customer's emails")
	void byIdAndByOrder() {
		Shopper shopper = register();
		Shopper stranger = register();
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-SUPP0101", shopper.id()));
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-SUPP0102", shopper.id()));
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-SUPP0103", stranger.id()));

		assertThat(notices("customerId=" + shopper.id(), support())).extracting(n -> n.get("orderNumber"))
				.containsExactlyInAnyOrder("FC-SUPP0101", "FC-SUPP0102");
		assertThat(notices("customerId=" + shopper.id() + "&orderNumber=FC-SUPP0102", support()))
				.extracting(n -> n.get("orderNumber")).containsExactly("FC-SUPP0102");

		assertThat(noticesStatus("", support())).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(noticesStatus("email=" + shopper.email() + "&customerId=" + shopper.id(), support()))
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(noticesStatus("email=nobody-" + UUID.randomUUID() + "@example.test", support()))
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("every support read of a customer's emails is recorded before it answers, and can itself be read")
	void readsAreRecorded() {
		Shopper shopper = register();
		String staff = "support-" + UUID.randomUUID();

		notices("customerId=" + shopper.id() + "&orderNumber=FC-SUPP0201", bearer(staff, AccessTokens.SUPPORT));

		assertThat(jdbc.queryForList("select operator_id, action, resource_id from operator_access_log where customer_id = ?",
				shopper.id())).containsExactly(Map.of("operator_id", staff, "action", "READ_NOTICES",
						"resource_id", "FC-SUPP0201"));

		ResponseEntity<List> log = rest.exchange("/api/v1/user/_access-log?customerId=" + shopper.id(), HttpMethod.GET,
				new HttpEntity<>(bearer(staff, AccessTokens.SUPPORT)), List.class);
		assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Newest first, and the newest is this read of the log.
		assertThat(log.getBody()).extracting(e -> ((Map<?, ?>) e).get("action"))
				.containsExactly("READ_ACCESS_LOG", "READ_NOTICES");
	}

	@Test
	@DisplayName("a customer can see when support read their emails -- not who, and only their own")
	void aCustomerReadsTheirOwnAccessLog() {
		Shopper shopper = register();
		Shopper stranger = register();
		notices("customerId=" + shopper.id(), support());
		notices("customerId=" + stranger.id(), support());

		ResponseEntity<List> mine = rest.exchange("/api/v1/user/_access-log/mine", HttpMethod.GET,
				new HttpEntity<>(bearer(shopper.id())), List.class);

		assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(mine.getBody()).hasSize(1);
		assertThat((Map<String, Object>) mine.getBody().get(0))
				.containsEntry("description", "A member of staff viewed the emails we sent you")
				.containsOnlyKeys("action", "description", "resourceId", "readAt");
		assertThat(jdbc.queryForObject("select count(*) from operator_access_log where customer_id = ?", Integer.class,
				shopper.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("only support reads a customer's emails: not the warehouse, not the customer, not anonymously")
	void onlySupport() {
		Shopper shopper = register();
		String query = "customerId=" + shopper.id();

		assertThat(noticesStatus(query, new HttpHeaders())).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(noticesStatus(query, bearer("wh-" + UUID.randomUUID(), AccessTokens.WAREHOUSE)))
				.isEqualTo(HttpStatus.FORBIDDEN);
		// Not even their own: what this shows -- retries, mail-server errors, skip reasons -- is for staff.
		assertThat(noticesStatus(query, bearer(shopper.id()))).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rest.exchange("/api/v1/user/_access-log?" + query, HttpMethod.GET,
				new HttpEntity<>(bearer(shopper.id())), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// The handler refuses on its own as well, so loosening the filter by mistake opens nothing. Called
		// directly, because through HTTP the filter answers first and this check is never reached.
		String customerToken = "Bearer " + tokens.issue(shopper.id(), shopper.email(), List.of());
		assertThatThrownBy(() -> support.notices(customerToken, null, UUID.fromString(shopper.id()), null, 50))
				.isInstanceOf(OperatorRequiredException.class);

		// A refused read leaves no record, because nothing was read.
		assertThat(jdbc.queryForObject("select count(*) from operator_access_log where customer_id = ?", Integer.class,
				shopper.id())).isZero();
	}

	// --- retention (ADR 0052) ----------------------------------------------------------------------

	@Test
	@DisplayName("finished emails about things past the window are deleted; recent ones and pending ones are kept")
	void retentionDeletesOnlyWhatIsFinishedAndOld() {
		String customer = UUID.randomUUID().toString();
		insert("sent-old", customer, "SENT", Duration.ofDays(91));
		insert("skipped-old", customer, "SKIPPED", Duration.ofDays(91));
		// Still owed, however old: the sender skips it as stale on its own terms, and then it may go.
		insert("pending-old", customer, "PENDING", Duration.ofDays(400));
		insert("sent-recent", customer, "SENT", Duration.ofDays(89));

		assertThat(retention.sweep()).isEqualTo(2);

		assertThat(jdbc.queryForList("select notice_key from notices where customer_id = ? order by notice_key",
				String.class, customer)).containsExactly("pending-old", "sent-recent");
		assertThat(meters.get("flashcart.user.notices.pruned").counter().count()).isGreaterThanOrEqualTo(2);
	}

	@Test
	@DisplayName("a backlog bigger than one batch is cleared in one sweep")
	void retentionWorksThroughBatches() {
		String customer = UUID.randomUUID().toString();
		for (int i = 0; i < 7; i++) {
			insert("backlog-" + i, customer, "SENT", Duration.ofDays(120));
		}
		NoticeRetention small = new NoticeRetention(jdbc, new SimpleMeterRegistry(), Duration.ofDays(90),
				Duration.ofDays(1), 2);

		assertThat(small.sweep()).isEqualTo(7);
		assertThat(jdbc.queryForObject("select count(*) from notices where customer_id = ?", Integer.class, customer))
				.isZero();
	}

	@Test
	@DisplayName("once its record is deleted, the same refund announced again is too old to email, not a second email")
	void aPrunedEmailIsNotSentAgain() {
		Shopper shopper = register();
		Instant refundedAt = Instant.now().minus(Duration.ofDays(100));
		PaymentRefunded original = new PaymentRefunded(meta(PaymentRefunded.TYPE, refundedAt), UUID.randomUUID().toString(),
				new BigDecimal("24.99"), "USD", "re_9", "FC-PRUNE001", shopper.id());
		// Sent at the time, then a hundred days pass and the record of it is deleted.
		jdbc.update("""
				insert into notices (notice_key, kind, customer_id, order_number, occurred_at, payload, status, sent_at)
				values ('refunded:FC-PRUNE001', 'REFUNDED', ?, 'FC-PRUNE001', ?, '{}', 'SENT', ?)""",
				shopper.id(), Timestamp.from(refundedAt), Timestamp.from(refundedAt));
		assertThat(retention.sweep()).isEqualTo(1);

		// Payment re-publishes a refund for a repeated command, now dated when the refund happened.
		listener.onPaymentRefunded(new PaymentRefunded(meta(PaymentRefunded.TYPE, refundedAt), original.paymentId(),
				original.amount(), original.currency(), original.providerReference(), original.orderNumber(),
				original.customerId()));
		sender.sendDue();

		assertThat(mail.sent).isEmpty();
		assertThat(row("refunded:FC-PRUNE001")).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "STALE");

		// Why payment had to change: dated now, as it used to be, the same refund would have been
		// emailed a second time.
		listener.onPaymentRefunded(new PaymentRefunded(meta(PaymentRefunded.TYPE), original.paymentId(),
				original.amount(), original.currency(), original.providerReference(), "FC-PRUNE002",
				original.customerId()));
		sender.sendDue();
		assertThat(mail.sent).hasSize(1);
	}

	@Test
	@DisplayName("a window no longer than the shelf life is refused at startup, because it would send emails twice")
	void retentionInsideTheShelfLifeIsRefused() {
		assertThatThrownBy(() -> new NoticeRetention(jdbc, new SimpleMeterRegistry(), Duration.ofDays(1),
				Duration.ofDays(1), 500))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must be longer than flashcart.notices.max-age");
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

	/**
	 * A notice in a given state, about something that happened {@code ago}. A pending one is backing off,
	 * not due, so the scheduled sender leaves it alone while the test looks at it.
	 */
	private void insert(String key, String customerId, String status, Duration ago) {
		Timestamp when = Timestamp.from(Instant.now().minus(ago));
		jdbc.update("""
				insert into notices (notice_key, kind, customer_id, occurred_at, status, skip_reason, sent_at, next_attempt_at)
				values (?, 'ORDER_CONFIRMED', ?, ?, ?, ?, ?, now() + interval '1 day')""",
				key, customerId, when, status, "SKIPPED".equals(status) ? "STALE" : null,
				"SENT".equals(status) ? when : null);
	}

	private HttpHeaders support() {
		return bearer("support-" + UUID.randomUUID(), AccessTokens.SUPPORT);
	}

	private HttpHeaders bearer(String subject, String... roles) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokens.issue(subject, subject + "@flashcart.test", List.of(roles)));
		return headers;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> notices(String query, HttpHeaders headers) {
		ResponseEntity<List> response = rest.exchange("/api/v1/user/_notices?" + query, HttpMethod.GET,
				new HttpEntity<>(headers), List.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private HttpStatusCode noticesStatus(String query, HttpHeaders headers) {
		return rest.exchange("/api/v1/user/_notices?" + query, HttpMethod.GET, new HttpEntity<>(headers), String.class)
				.getStatusCode();
	}

	private Map<String, Object> row(String key) {
		return jdbc.queryForMap("select * from notices where notice_key = ?", key);
	}

	private double gauge() {
		return meters.get("flashcart.user.notices.oldest.pending.seconds").gauge().value();
	}
}
