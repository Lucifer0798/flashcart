package com.flashcart.user.notice;

import java.time.Duration;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending an email again, at support's request. See ADR 0064.
 *
 * <p>Only an email that would still be true. An order email and "your password was changed" state facts that
 * stay true, so they can always go again. A back-in-stock email promises a held unit, and once the hold has
 * lapsed that unit has gone to the next in line, so it is refused. A reset link is never sent again: the
 * token is minted at send and the request it belonged to may be spent, so the customer asks for a new one.
 * And nothing goes to a closed account.
 *
 * <p>An email still waiting is retried now, in place. One already sent or skipped is queued again as a new
 * email, so the original keeps its history: when it went, or why it did not.
 */
@Component
public class NoticeResends {

	private static final Logger log = LoggerFactory.getLogger(NoticeResends.class);

	/** What a resend did. */
	public enum Outcome { RETRIED, QUEUED }

	/** @param key the email that will now be sent: the same one, retried, or the new copy */
	public record Resend(Outcome outcome, String key) {
	}

	private final JdbcTemplate jdbc;
	private final Duration maxAge;

	public NoticeResends(JdbcTemplate jdbc, @Value("${flashcart.notices.max-age:P1D}") Duration maxAge) {
		this.jdbc = jdbc;
		this.maxAge = maxAge;
	}

	/** Whose email this is, for the caller to check and record before anything is sent. */
	public String ownerOf(String key) {
		return jdbc.query("select customer_id from notices where notice_key = ?",
				rs -> rs.next() ? rs.getString(1) : null, key);
	}

	@Transactional
	public Resend resend(String key) {
		Row notice = jdbc.query("""
				select kind, status, customer_id,
				       -- Asked of the database, against the clock that set the deadline.
				       send_before is not null and send_before <= now() as past_deadline
				  from notices where notice_key = ? for update""",
				rs -> rs.next() ? new Row(NoticeKind.valueOf(rs.getString("kind")), rs.getString("status"),
						rs.getString("customer_id"), rs.getBoolean("past_deadline")) : null, key);
		if (notice == null) {
			throw ResourceNotFoundException.of("Email", key);
		}
		NoticeKind kind = notice.kind();
		if (kind == NoticeKind.PASSWORD_RESET) {
			throw new ConflictException("NOT_RESENDABLE", "A reset link is not sent again. The customer can ask for a "
					+ "new one, which is sent with a fresh link.");
		}
		Boolean active = jdbc.queryForObject("""
				select exists (select 1 from users where id::text = ? and status = 'ACTIVE')""", Boolean.class,
				notice.customerId());
		if (!Boolean.TRUE.equals(active)) {
			throw new ConflictException("NO_RECIPIENT", "The account this email was for is closed or disabled.");
		}
		boolean hold = kind == NoticeKind.BACK_IN_STOCK;
		if (hold && notice.pastDeadline()) {
			throw new ConflictException("HOLD_LAPSED", "The unit held for this shopper has already gone to the next "
					+ "in line; telling them it is theirs would be false.");
		}

		if ("PENDING".equals(notice.status())) {
			// Still waiting, most likely on a mail server that refused it. Try now, rather than at the end of its
			// backoff. A shelf life is extended for an email that stays true, so a long outage does not skip it.
			jdbc.update(hold
					? "update notices set next_attempt_at = now() where notice_key = ?"
					: "update notices set next_attempt_at = now(), send_before = now() + make_interval(secs => ?) "
							+ "where notice_key = ?",
					hold ? new Object[] { key } : new Object[] { maxAge.toSeconds(), key });
			log.info("Retrying email {} now, at support's request", key);
			return new Resend(Outcome.RETRIED, key);
		}

		// Sent or skipped: a new email, so the original's record of what happened stands. Same occurred_at, so it
		// takes its place in its order's sequence; a fresh shelf life, except for a hold, which keeps its own.
		String copy = key + ":resend:" + System.currentTimeMillis();
		jdbc.update("""
				insert into notices (notice_key, kind, customer_id, order_number, occurred_at, payload, send_before, status)
				select ?, kind, customer_id, order_number, occurred_at, payload,
				       case when kind = 'BACK_IN_STOCK' then send_before else now() + make_interval(secs => ?) end,
				       'PENDING'
				  from notices where notice_key = ?""", copy, maxAge.toSeconds(), key);
		log.info("Queued email {} again as {}, at support's request", key, copy);
		return new Resend(Outcome.QUEUED, copy);
	}

	private record Row(NoticeKind kind, String status, String customerId, boolean pastDeadline) {
	}
}
