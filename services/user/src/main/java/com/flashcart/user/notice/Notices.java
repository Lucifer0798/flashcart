package com.flashcart.user.notice;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The emails waiting to be sent, one row per thing worth telling somebody. See ADRs 0046 and 0048.
 */
@Component
public class Notices {

	private static final Logger log = LoggerFactory.getLogger(Notices.class);

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public Notices(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** One email to send, before it is sent. */
	public record Notice(String key, NoticeKind kind, String customerId, String orderNumber, Instant occurredAt,
			Map<String, Object> payload, Instant sendBefore) {
	}

	/**
	 * Record an email to send. A second notice with the same key -- a redelivery, or the same thing
	 * announced twice -- is dropped here rather than becoming a second email. The processed-events claim
	 * cannot do that alone: it dedupes by event id, and a re-published event has a new one.
	 *
	 * @return true if this was new
	 */
	public boolean receive(Notice notice) {
		int inserted = jdbc.update("""
				insert into notices
				       (notice_key, kind, customer_id, order_number, occurred_at, payload, send_before, status)
				values (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING')
				on conflict (notice_key) do nothing""",
				notice.key(), notice.kind().name(), notice.customerId(), notice.orderNumber(),
				Timestamp.from(notice.occurredAt()), json.writeValueAsString(notice.payload()),
				notice.sendBefore() == null ? null : Timestamp.from(notice.sendBefore()));
		if (inserted == 0) {
			log.info("Already have notice {}; not sending another", notice.key());
		}
		return inserted == 1;
	}

	/** Seconds the oldest unsent notice has been waiting, or zero when nothing is waiting. */
	public long oldestPendingSeconds(Instant now) {
		Timestamp oldest = jdbc.queryForObject(
				"select min(created_at) from notices where status = 'PENDING'", Timestamp.class);
		return oldest == null ? 0 : Math.max(0, now.getEpochSecond() - oldest.toInstant().getEpochSecond());
	}
}
