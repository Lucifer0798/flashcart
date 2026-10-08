package com.flashcart.user.notice;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the back-in-stock notices this service has received, for as long as the mail server takes.
 *
 * <h2>At least once, and as close to once as SMTP allows</h2>
 *
 * Each notice is sent inside a transaction that holds its row, and marked sent in the same one. A
 * second sender skips a row another holds, and a sent row is never claimed again. What remains is the
 * one gap no mail protocol closes: the server accepts the message and the commit that records it then
 * fails. That notice is sent again on the next attempt. Exactly once would need the mail server to
 * take part in the transaction, and none does. See ADR 0046.
 *
 * <h2>When the mail server is down</h2>
 *
 * The notice stays {@code PENDING} and is tried again with growing gaps -- thirty seconds, a minute,
 * two, capped at thirty minutes -- with no attempt limit, because nothing gets better by giving up on
 * telling somebody their unit is held. What does change is whether there is still anything to tell:
 * a notice whose held unit has already been released is not sent late, because "we are holding one
 * for you" would be false by the time it arrived. {@code flashcart_user_notices_oldest_pending_seconds}
 * says how far behind sending is, and an alert fires on it.
 */
@Component
public class BackInStockSender {

	private static final Logger log = LoggerFactory.getLogger(BackInStockSender.class);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	/**
	 * The database's clock, not this JVM's, decides what is due -- and the same clock writes
	 * {@code next_attempt_at} and the row's default {@code created_at}. Mixing the two made a notice
	 * made due "now" by one clock not yet due by the other whenever the database's ran ahead, which the
	 * Postgres container's does: a test passed alone and failed in the module run. One clock for one queue.
	 */
	private static final String CLAIM_DUE = """
			select waitlist_entry_id, customer_id, sku, held_until, attempts
			  from back_in_stock_notices
			 where status = 'PENDING' and next_attempt_at <= now()
			 order by next_attempt_at
			 limit 1
			   for update skip locked""";

	private final JdbcTemplate jdbc;
	private final BackInStockNotices notices;
	private final JavaMailSender mail;
	private final TransactionTemplate transactions;
	private final String from;
	private final int batchSize;
	private final AtomicLong oldestPending = new AtomicLong();
	private final Counter sent;
	private final Counter failed;
	private final Counter skipped;

	public BackInStockSender(JdbcTemplate jdbc, BackInStockNotices notices, JavaMailSender mail,
			PlatformTransactionManager transactionManager, MeterRegistry meters,
			@Value("${flashcart.notices.from:FlashCart <no-reply@flashcart.local>}") String from,
			@Value("${flashcart.notices.batch-size:50}") int batchSize) {
		this.jdbc = jdbc;
		this.notices = notices;
		this.mail = mail;
		this.transactions = new TransactionTemplate(transactionManager);
		this.from = from;
		this.batchSize = batchSize;
		Gauge.builder("flashcart.user.notices.oldest.pending.seconds", oldestPending, AtomicLong::get)
				.description("How long the oldest unsent back-in-stock notice has been waiting")
				.register(meters);
		this.sent = Counter.builder("flashcart.user.notices").tag("outcome", "sent")
				.description("Back-in-stock notices, by what happened to them").register(meters);
		this.failed = Counter.builder("flashcart.user.notices").tag("outcome", "failed")
				.description("Back-in-stock notices, by what happened to them").register(meters);
		this.skipped = Counter.builder("flashcart.user.notices").tag("outcome", "skipped")
				.description("Back-in-stock notices, by what happened to them").register(meters);
	}

	@Scheduled(fixedDelayString = "${flashcart.notices.poll:PT2S}", initialDelayString = "${flashcart.notices.initial-delay:PT5S}")
	public void tick() {
		try {
			sendDue();
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws can stop being rescheduled, and this one stopping is
			// exactly the silent failure the gauge below exists to expose.
			log.error("Sending back-in-stock notices failed; will try again", ex);
		}
		finally {
			refreshGauge();
		}
	}

	/**
	 * Send everything due now, one notice per transaction. Exposed so tests can drive it directly.
	 *
	 * <p>Stops at the first refusal. When the mail server is down every notice behind the first would
	 * spend its own timeout learning the same thing, and the scheduler thread with it; they stay due and
	 * the next tick tries again.
	 */
	public int sendDue() {
		int handled = 0;
		while (handled < batchSize) {
			Outcome outcome = transactions.execute(status -> sendOne(Instant.now()));
			if (outcome == null || outcome == Outcome.NOTHING_DUE) {
				break;
			}
			handled++;
			if (outcome == Outcome.REFUSED) {
				break;
			}
		}
		return handled;
	}

	private enum Outcome { NOTHING_DUE, HANDLED, REFUSED }

	public void refreshGauge() {
		try {
			oldestPending.set(notices.oldestPendingSeconds(Instant.now()));
		}
		catch (RuntimeException ex) {
			// Keep the last value: reading zero because the count failed would resolve the alert.
			log.warn("Could not measure the notice backlog; keeping {}s", oldestPending.get(), ex);
		}
	}

	private Outcome sendOne(Instant now) {
		List<Map<String, Object>> due = jdbc.queryForList(CLAIM_DUE);
		if (due.isEmpty()) {
			return Outcome.NOTHING_DUE;
		}
		Map<String, Object> row = due.get(0);
		UUID entryId = (UUID) row.get("waitlist_entry_id");
		String customerId = (String) row.get("customer_id");
		String sku = (String) row.get("sku");
		Timestamp heldUntilTs = (Timestamp) row.get("held_until");
		Instant heldUntil = heldUntilTs == null ? null : heldUntilTs.toInstant();
		int attempts = ((Number) row.get("attempts")).intValue();

		if (heldUntil != null && !heldUntil.isAfter(now)) {
			// The unit was held for them and has gone on to the next in line. Telling them now that
			// it is held would be false, and telling them it is back would send them after a unit
			// that is somebody else's.
			skip(entryId, "HOLD_LAPSED");
			return Outcome.HANDLED;
		}

		Recipient recipient = recipient(customerId);
		if (recipient == null) {
			skip(entryId, "NO_RECIPIENT");
			return Outcome.HANDLED;
		}

		try {
			mail.send(message(recipient, sku, heldUntil));
		}
		catch (MailException ex) {
			Duration wait = backoff(attempts + 1);
			jdbc.update("""
					update back_in_stock_notices
					   set attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => ?), last_error = ?
					 where waitlist_entry_id = ?""",
					wait.toSeconds(), truncate(ex.getMessage()), entryId);
			failed.increment();
			log.warn("Could not send back-in-stock notice for {} to {} (attempt {}); trying again in {}",
					sku, customerId, attempts + 1, wait);
			return Outcome.REFUSED;
		}

		jdbc.update("update back_in_stock_notices set status = 'SENT', sent_at = now(), attempts = attempts + 1 "
				+ "where waitlist_entry_id = ?", entryId);
		sent.increment();
		log.info("Told {} that {} is back", customerId, sku);
		return Outcome.HANDLED;
	}

	private record Recipient(String email, String name) {
	}

	private Recipient recipient(String customerId) {
		UUID userId;
		try {
			userId = UUID.fromString(customerId);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		return jdbc.query("select email, display_name from users where id = ?",
				rs -> rs.next() ? new Recipient(rs.getString("email"), rs.getString("display_name")) : null,
				userId);
	}

	private SimpleMailMessage message(Recipient recipient, String sku, Instant heldUntil) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipient.email());
		message.setSubject("Back in stock: " + sku);
		String held = heldUntil == null
				? "It may not last, so if you still want it, now is the time."
				: ("We are holding one for you until %s. Check out before then and it is yours; after that it "
						+ "goes to the next person waiting.").formatted(TIME.format(heldUntil));
		message.setText("""
				Hi %s,

				%s is back in stock, and you were next in line.

				%s

				-- FlashCart
				""".formatted(recipient.name(), sku, held));
		return message;
	}

	private void skip(UUID entryId, String reason) {
		jdbc.update("update back_in_stock_notices set status = 'SKIPPED', skip_reason = ? where waitlist_entry_id = ?",
				reason, entryId);
		skipped.increment();
		log.info("Not sending back-in-stock notice {}: {}", entryId, reason);
	}

	/** Thirty seconds, doubling, capped at thirty minutes. No limit on attempts: see the class comment. */
	public static Duration backoff(int attempt) {
		long seconds = 30L << Math.min(attempt - 1, 6);
		return Duration.ofSeconds(Math.min(seconds, 1_800));
	}

	private static String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= 500 ? message : message.substring(0, 500);
	}
}
