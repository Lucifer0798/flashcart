package com.flashcart.user.notice;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What the platform has emailed one customer, or tried to. See ADR 0051.
 *
 * <p>Reads the queue itself rather than a copy: the row that decided whether to send is the row support
 * sees, so the answer cannot disagree with what happened. Says nothing about who may ask; that is the
 * caller's to check and record.
 */
@Component
public class NoticeHistory {

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public NoticeHistory(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * One email, as support needs to see it.
	 *
	 * @param subject       the subject line, which is what a customer searching their inbox will look for
	 * @param body          the text, without the greeting. Rendered now from what was stored, not kept
	 *                      from the send: the same words unless the wording has changed since
	 * @param status        PENDING, SENT or SKIPPED
	 * @param skipReason    STALE or NO_RECIPIENT when skipped; why the customer never got it
	 * @param lastError     the mail server's last refusal, if there was one. Kept once a later attempt
	 *                      succeeds: with {@code attempts}, it is why a sent email went out late
	 * @param nextAttemptAt when it will next be tried; null unless pending
	 */
	public record Entry(String kind, String subject, String body, String orderNumber, String status,
			String skipReason, int attempts, String lastError, Instant occurredAt, Instant receivedAt,
			Instant sentAt, Instant nextAttemptAt, Instant sendBefore) {
	}

	/** Newest first, by when it happened. {@code orderNumber} narrows to one order when given. */
	public List<Entry> forCustomer(String customerId, String orderNumber, int limit) {
		return jdbc.query("""
				select kind, order_number, payload::text as payload, status, skip_reason, attempts, last_error,
				       occurred_at, created_at, sent_at, next_attempt_at, send_before
				  from notices
				 where customer_id = ?
				   and (cast(? as varchar) is null or order_number = ?)
				 order by occurred_at desc, notice_key
				 limit ?""",
				(rs, row) -> entry(rs), customerId, orderNumber, orderNumber, limit);
	}

	private Entry entry(ResultSet rs) throws SQLException {
		NoticeKind kind = NoticeKind.valueOf(rs.getString("kind"));
		String orderNumber = rs.getString("order_number");
		Instant sendBefore = instant(rs.getTimestamp("send_before"));
		Map<String, Object> payload = json.readValue(rs.getString("payload"), new TypeReference<>() { });
		// The name only appears in the greeting, which text() adds and this leaves out.
		NoticeKind.Email email = kind.render(null, orderNumber, payload, sendBefore);
		String status = rs.getString("status");
		return new Entry(kind.name(), email.subject(), email.body(), orderNumber, status,
				rs.getString("skip_reason"), rs.getInt("attempts"), rs.getString("last_error"),
				instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")),
				instant(rs.getTimestamp("sent_at")),
				"PENDING".equals(status) ? instant(rs.getTimestamp("next_attempt_at")) : null, sendBefore);
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
