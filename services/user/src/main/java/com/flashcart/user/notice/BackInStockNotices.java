package com.flashcart.user.notice;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.flashcart.common.event.message.BackInStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The notices waiting to be sent, keyed by the place in the queue they are for. See ADR 0046.
 */
@Component
public class BackInStockNotices {

	private static final Logger log = LoggerFactory.getLogger(BackInStockNotices.class);

	private final JdbcTemplate jdbc;

	public BackInStockNotices(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Record a notice to send. A second notice for the same place in the queue -- a redelivery, or the
	 * same decision published twice -- is dropped here, by the primary key, rather than becoming a
	 * second email. The processed-events claim cannot do that alone: it dedupes by event id, and a
	 * re-published event has a new one.
	 *
	 * @return true if this was new
	 */
	public boolean receive(BackInStock event) {
		int inserted = jdbc.update("""
				insert into back_in_stock_notices
				       (waitlist_entry_id, customer_id, sku, notified_at, held_until, status)
				values (?, ?, ?, ?, ?, 'PENDING')
				on conflict (waitlist_entry_id) do nothing""",
				UUID.fromString(event.waitlistEntryId()), event.customerId(), event.sku(),
				Timestamp.from(event.notifiedAt()),
				event.heldUntil() == null ? null : Timestamp.from(event.heldUntil()));
		if (inserted == 0) {
			log.info("Already have a notice for waitlist entry {}; not sending another", event.waitlistEntryId());
		}
		return inserted == 1;
	}

	/** Seconds the oldest unsent notice has been waiting, or zero when nothing is waiting. */
	public long oldestPendingSeconds(Instant now) {
		Timestamp oldest = jdbc.queryForObject(
				"select min(created_at) from back_in_stock_notices where status = 'PENDING'", Timestamp.class);
		return oldest == null ? 0 : Math.max(0, now.getEpochSecond() - oldest.toInstant().getEpochSecond());
	}
}
