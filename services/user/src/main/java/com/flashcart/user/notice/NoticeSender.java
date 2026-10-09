package com.flashcart.user.notice;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends the emails this service has received, for as long as the mail server takes. See ADRs 0046 and 0048.
 *
 * <h2>At least once, and as close to once as SMTP allows</h2>
 *
 * Each notice is sent inside a transaction that holds its row, and marked sent in the same one. What
 * remains is the one gap no mail protocol closes: the server accepts the message and the commit that
 * records it then fails. That notice is sent again.
 *
 * <h2>When the mail server is down</h2>
 *
 * The notice stays {@code PENDING} and is retried with growing gaps -- thirty seconds, doubling, capped
 * at thirty minutes -- with no attempt limit. A run stops at the first refusal, so an outage costs one
 * timeout per tick rather than one per notice. A notice that would be false by the time it went -- its
 * {@code send_before} has passed -- is skipped as stale instead.
 *
 * <h2>In the order things happened</h2>
 *
 * Emails about one order are sent in the order the events occurred. A notice is not due while an older
 * one for the same order is still pending, so a "confirmed" held back by a retry is never overtaken by
 * the "dispatched" behind it. The sequence is the publisher's {@code occurred_at}, not when this service
 * heard: separate consumer groups can hear out of order.
 */
@Component
public class NoticeSender {

	private static final Logger log = LoggerFactory.getLogger(NoticeSender.class);

	/**
	 * The database's clock decides what is due, and the same clock writes {@code next_attempt_at}: mixing
	 * it with this JVM's made a notice due by one clock not yet due by the other (ADR 0046).
	 */
	private static final String CLAIM_DUE = """
			select n.notice_key, n.kind, n.customer_id, n.order_number, n.payload::text as payload,
			       n.send_before, n.attempts
			  from notices n
			 where n.status = 'PENDING'
			   and n.next_attempt_at <= now()
			   and not exists (select 1
			                     from notices earlier
			                    where earlier.order_number = n.order_number
			                      and earlier.status = 'PENDING'
			                      and (earlier.occurred_at, earlier.notice_key) < (n.occurred_at, n.notice_key))
			 order by n.next_attempt_at, n.occurred_at
			 limit 1
			   for update of n skip locked""";

	private final JdbcTemplate jdbc;
	private final Notices notices;
	private final JavaMailSender mail;
	private final ObjectMapper json;
	private final MeterRegistry meters;
	private final TransactionTemplate transactions;
	private final String from;
	private final int batchSize;
	private final AtomicLong oldestPending = new AtomicLong();

	public NoticeSender(JdbcTemplate jdbc, Notices notices, JavaMailSender mail, ObjectMapper json,
			PlatformTransactionManager transactionManager, MeterRegistry meters,
			@Value("${flashcart.notices.from:FlashCart <no-reply@flashcart.local>}") String from,
			@Value("${flashcart.notices.batch-size:50}") int batchSize) {
		this.jdbc = jdbc;
		this.notices = notices;
		this.mail = mail;
		this.json = json;
		this.meters = meters;
		this.transactions = new TransactionTemplate(transactionManager);
		this.from = from;
		this.batchSize = batchSize;
		Gauge.builder("flashcart.user.notices.oldest.pending.seconds", oldestPending, AtomicLong::get)
				.description("How long the oldest unsent email has been waiting")
				.register(meters);
	}

	@Scheduled(fixedDelayString = "${flashcart.notices.poll:PT2S}", initialDelayString = "${flashcart.notices.initial-delay:PT5S}")
	public void tick() {
		try {
			sendDue();
		}
		catch (RuntimeException ex) {
			log.error("Sending notices failed; will try again", ex);
		}
		finally {
			refreshGauge();
		}
	}

	/** Send everything due now, one notice per transaction, stopping at the first refusal. */
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

	public void refreshGauge() {
		try {
			oldestPending.set(notices.oldestPendingSeconds(Instant.now()));
		}
		catch (RuntimeException ex) {
			// Keep the last value: reading zero because the count failed would resolve the alert.
			log.warn("Could not measure the notice backlog; keeping {}s", oldestPending.get(), ex);
		}
	}

	private enum Outcome { NOTHING_DUE, HANDLED, REFUSED }

	private Outcome sendOne(Instant now) {
		List<Map<String, Object>> due = jdbc.queryForList(CLAIM_DUE);
		if (due.isEmpty()) {
			return Outcome.NOTHING_DUE;
		}
		Map<String, Object> row = due.get(0);
		String key = (String) row.get("notice_key");
		NoticeKind kind = NoticeKind.valueOf((String) row.get("kind"));
		Timestamp sendBeforeTs = (Timestamp) row.get("send_before");
		Instant sendBefore = sendBeforeTs == null ? null : sendBeforeTs.toInstant();
		int attempts = ((Number) row.get("attempts")).intValue();

		if (sendBefore != null && !sendBefore.isAfter(now)) {
			// It would be false by the time it arrived: for back-in-stock, the unit held for them has
			// already gone to the next in line.
			skip(key, kind, "STALE");
			return Outcome.HANDLED;
		}

		Recipient recipient = recipient((String) row.get("customer_id"));
		if (recipient == null) {
			skip(key, kind, "NO_RECIPIENT");
			return Outcome.HANDLED;
		}

		Map<String, Object> payload = json.readValue((String) row.get("payload"), new TypeReference<>() { });
		NoticeKind.Email email = kind.render(recipient.name(), (String) row.get("order_number"), payload, sendBefore);
		try {
			mail.send(message(recipient.email(), email));
		}
		catch (MailException ex) {
			Duration wait = backoff(attempts + 1);
			jdbc.update("""
					update notices
					   set attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => ?), last_error = ?
					 where notice_key = ?""",
					wait.toSeconds(), truncate(ex.getMessage()), key);
			count(kind, "failed");
			log.warn("Could not send notice {} (attempt {}); trying again in {}", key, attempts + 1, wait);
			return Outcome.REFUSED;
		}

		jdbc.update("update notices set status = 'SENT', sent_at = now(), attempts = attempts + 1 where notice_key = ?",
				key);
		count(kind, "sent");
		log.info("Sent notice {}", key);
		return Outcome.HANDLED;
	}

	private record Recipient(String email, String name) {
	}

	private Recipient recipient(String customerId) {
		if (customerId == null) {
			return null;
		}
		java.util.UUID userId;
		try {
			userId = java.util.UUID.fromString(customerId);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		return jdbc.query("select email, display_name from users where id = ?",
				rs -> rs.next() ? new Recipient(rs.getString("email"), rs.getString("display_name")) : null,
				userId);
	}

	private SimpleMailMessage message(String to, NoticeKind.Email email) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(email.subject());
		message.setText(email.text());
		return message;
	}

	private void skip(String key, NoticeKind kind, String reason) {
		jdbc.update("update notices set status = 'SKIPPED', skip_reason = ? where notice_key = ?", reason, key);
		count(kind, "skipped");
		log.info("Not sending notice {}: {}", key, reason);
	}

	private void count(NoticeKind kind, String outcome) {
		meters.counter("flashcart.user.notices", "kind", kind.name(), "outcome", outcome).increment();
	}

	/** Thirty seconds, doubling, capped at thirty minutes. No limit on attempts. */
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
