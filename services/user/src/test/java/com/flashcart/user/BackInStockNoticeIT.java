package com.flashcart.user;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.message.BackInStock;
import com.flashcart.user.messaging.BackInStockListener;
import com.flashcart.user.notice.BackInStockSender;
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
 * The user service telling a shopper their SKU is back. See ADR 0046.
 *
 * <p>The mail server is a recording stand-in that can be told to refuse, because "the mail server is
 * down" is the case this design exists for and a real one cannot be switched off on demand. The real
 * SMTP path is exercised against Mailpit in the compose stack and in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class BackInStockNoticeIT {

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
	private BackInStockListener listener;

	@Autowired
	private BackInStockSender sender;

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
		jdbc.update("delete from back_in_stock_notices");
	}

	@Test
	@DisplayName("a shopper told by inventory gets one email, saying until when the unit is held")
	void toldMeansEmailed() {
		Shopper shopper = register();
		Instant heldUntil = Instant.parse("2099-01-01T12:34:00Z");

		listener.onBackInStock(notice(UUID.randomUUID(), shopper.id(), "AUD-HP-001", heldUntil));
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

		// Two events for one place: a redelivery has the same event id and is caught by the claim, but a
		// re-publication has a new one, and only the notice's own key stops it becoming a second email.
		listener.onBackInStock(notice(entry, shopper.id(), "AUD-HP-001", future()));
		listener.onBackInStock(notice(entry, shopper.id(), "AUD-HP-001", future()));
		sender.sendDue();
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
	}

	@Test
	@DisplayName("a mail server that is down delays the notice; it is sent when the server is back")
	void mailDownIsADelayNotALoss() {
		Shopper shopper = register();
		UUID entry = UUID.randomUUID();
		listener.onBackInStock(notice(entry, shopper.id(), "AUD-HP-001", future()));

		mail.down = true;
		sender.sendDue();

		Map<String, Object> row = row(entry);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("attempts")).isEqualTo(1);
		assertThat((String) row.get("last_error")).contains("mail server is down");
		// Not retried at once: the next attempt waits, so an outage is not hammered every two seconds.
		assertThat(sender.sendDue()).isZero();
		assertThat(mail.sent).isEmpty();

		mail.down = false;
		jdbc.update("update back_in_stock_notices set next_attempt_at = now() where waitlist_entry_id = ?", entry);
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(row(entry).get("status")).isEqualTo("SENT");
	}

	@Test
	@DisplayName("one refusal ends the batch: the rest wait for the next tick, not each for its own timeout")
	void refusalStopsTheBatch() {
		Shopper shopper = register();
		for (int i = 0; i < 3; i++) {
			listener.onBackInStock(notice(UUID.randomUUID(), shopper.id(), "AUD-HP-00" + i, future()));
		}

		mail.down = true;
		assertThat(sender.sendDue()).isEqualTo(1);

		// One attempted, two untouched and still due.
		assertThat(jdbc.queryForList("select attempts from back_in_stock_notices order by attempts", Integer.class))
				.containsExactly(0, 0, 1);
	}

	@Test
	@DisplayName("a mail outage does not make the user service unhealthy")
	void mailOutageIsNotAServiceOutage() {
		// No mail server is reachable in this test at all; the stand-in sender is not what health checks.
		Map<?, ?> health = rest.getForObject("/actuator/health", Map.class);

		assertThat(health.get("status")).isEqualTo("UP");
	}

	@Test
	@DisplayName("a notice whose held unit has already gone to the next in line is not sent late")
	void lapsedHoldIsNotEmailed() {
		Shopper shopper = register();
		UUID entry = UUID.randomUUID();

		listener.onBackInStock(notice(entry, shopper.id(), "AUD-HP-001", Instant.now().minusSeconds(60)));
		sender.sendDue();

		assertThat(mail.sent).isEmpty();
		assertThat(row(entry)).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "HOLD_LAPSED");
	}

	@Test
	@DisplayName("a customer this service does not know is skipped, not retried for ever")
	void unknownCustomerIsSkipped() {
		UUID entry = UUID.randomUUID();

		listener.onBackInStock(notice(entry, "not-a-user", "AUD-HP-001", future()));
		sender.sendDue();

		assertThat(mail.sent).isEmpty();
		assertThat(row(entry)).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "NO_RECIPIENT");
	}

	@Test
	@DisplayName("a notice with no unit held still goes, and does not promise one")
	void noHoldStillTold() {
		Shopper shopper = register();

		listener.onBackInStock(notice(UUID.randomUUID(), shopper.id(), "AUD-HP-001", null));
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.get(0).getText()).doesNotContain("holding one for you");
	}

	@Test
	@DisplayName("the backlog gauge says how far behind sending is, and returns to zero when caught up")
	void backlogIsMeasured() {
		Shopper shopper = register();
		UUID entry = UUID.randomUUID();
		listener.onBackInStock(notice(entry, shopper.id(), "AUD-HP-001", future()));
		jdbc.update("update back_in_stock_notices set created_at = now() - interval '15 minutes' "
				+ "where waitlist_entry_id = ?", entry);

		sender.refreshGauge();
		assertThat(gauge()).isGreaterThanOrEqualTo(15 * 60);

		sender.sendDue();
		sender.refreshGauge();
		assertThat(gauge()).isZero();
	}

	@Test
	@DisplayName("retries back off from thirty seconds to a ceiling of thirty minutes")
	void backoff() {
		assertThat(BackInStockSender.backoff(1)).isEqualTo(Duration.ofSeconds(30));
		assertThat(BackInStockSender.backoff(2)).isEqualTo(Duration.ofMinutes(1));
		assertThat(BackInStockSender.backoff(4)).isEqualTo(Duration.ofMinutes(4));
		assertThat(BackInStockSender.backoff(7)).isEqualTo(Duration.ofMinutes(30));
		assertThat(BackInStockSender.backoff(50)).isEqualTo(Duration.ofMinutes(30));
	}

	// --- helpers ------------------------------------------------------------------------------------

	private record Shopper(String id, String email) {
	}

	@SuppressWarnings("unchecked")
	private Shopper register() {
		String email = "waiter-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		Map<String, Object> created = rest.postForObject("/api/v1/users",
				Map.of("email", email, "password", "a-sufficiently-long-password", "displayName", "Patient Shopper"),
				Map.class);
		return new Shopper((String) created.get("id"), email);
	}

	private static BackInStock notice(UUID entry, String customerId, String sku, Instant heldUntil) {
		return new BackInStock(EventMetadata.of(BackInStock.TYPE, entry), entry.toString(), sku, customerId,
				Instant.now(), heldUntil);
	}

	private static Instant future() {
		return Instant.now().plus(Duration.ofMinutes(10));
	}

	private Map<String, Object> row(UUID entry) {
		return jdbc.queryForMap("select * from back_in_stock_notices where waitlist_entry_id = ?", entry);
	}

	private double gauge() {
		return meters.get("flashcart.user.notices.oldest.pending.seconds").gauge().value();
	}
}
