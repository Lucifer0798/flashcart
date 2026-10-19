package com.flashcart.user.notice;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes emails that are finished with, once they are old enough. See ADR 0052.
 *
 * <p>A notice holds a customer id, an order number and what the email said, down to the reference a
 * refund was paid under. Nothing deleted one, so the table kept all of that for every customer for
 * ever, and grew with every order.
 *
 * <h2>Deleting a notice is what lets the email be sent again</h2>
 *
 * <p>A row is more than history. Its key is what makes a repeated announcement a no-op rather than a
 * second email, so deleting it is to {@code notices} what pruning is to {@code processed_events}: that
 * email becomes sendable again. It stays safe because of the shelf life. Every email-producing event
 * carries when its fact happened ({@code DomainEvent#occurredAt}), and a notice that arrives later than
 * {@code flashcart.notices.max-age} after that is skipped as stale. Prune only notices about things
 * older than the shelf life, and any copy of the same announcement that arrives afterwards is already
 * stale. That holds for a redelivery and for a re-publish alike, provided a re-publish keeps the
 * fact's own time, which is why payment now stamps a re-sent refund with when it happened. So the
 * window is measured from {@code occurred_at}, and a window not longer than the shelf life is refused
 * at startup rather than quietly allowed to send something twice.
 *
 * <h2>Never a pending one</h2>
 *
 * <p>Only SENT and SKIPPED rows are touched. A PENDING row is an email still owed, however old: the
 * sender skips it as stale on its own terms, and then this may delete it.
 */
@Component
public class NoticeRetention {

	private static final Logger log = LoggerFactory.getLogger(NoticeRetention.class);

	/**
	 * Batched, for the reason the outbox's sweeper is: the sender is working on this table, and one
	 * enormous delete on a first run against a year of rows would hold it up.
	 */
	private static final String PRUNE = """
			delete from notices
			 where notice_key in (
			       select notice_key from notices
			        where status in ('SENT', 'SKIPPED')
			          and occurred_at < now() - make_interval(secs => ?)
			        limit ?)
			""";

	/** Enough to clear a flash sale's worth in one sweep without the sweep becoming the long job. */
	private static final int MAX_BATCHES_PER_SWEEP = 20;

	private final JdbcTemplate jdbc;
	private final Duration retention;
	private final int batchSize;
	private final Counter pruned;

	public NoticeRetention(JdbcTemplate jdbc, MeterRegistry meters,
			@Value("${flashcart.notices.retention:P90D}") Duration retention,
			@Value("${flashcart.notices.max-age:P1D}") Duration maxAge,
			@Value("${flashcart.notices.retention-batch-size:500}") int batchSize) {
		if (retention.compareTo(maxAge) <= 0) {
			// Not clamped, and not a warning: a window inside the shelf life lets a redelivered or
			// re-published event email a customer a second time, and the person who set it should find
			// out at deploy rather than from a customer.
			throw new IllegalStateException(("flashcart.notices.retention (%s) must be longer than "
					+ "flashcart.notices.max-age (%s): a notice deleted while its event can still arrive "
					+ "inside the shelf life would be sent again").formatted(retention, maxAge));
		}
		this.jdbc = jdbc;
		this.retention = retention;
		this.batchSize = batchSize;
		this.pruned = Counter.builder("flashcart.user.notices.pruned")
				.description("Finished emails deleted for age")
				.register(meters);
	}

	@Scheduled(fixedDelayString = "${flashcart.notices.retention-fixed-delay:PT1H}",
			initialDelayString = "${flashcart.notices.retention-initial-delay:PT5M}")
	public void prune() {
		try {
			sweep();
		}
		catch (DataAccessException ex) {
			// A scheduled method that throws may stop being rescheduled, and a retention job that quietly
			// stops is a table that grows again with no trace of when it began.
			log.error("Notice retention sweep failed; will retry on the next tick", ex);
		}
	}

	/** @return how many were deleted */
	public int sweep() {
		int total = 0;
		for (int batch = 0; batch < MAX_BATCHES_PER_SWEEP; batch++) {
			int deleted = jdbc.update(PRUNE, retention.toSeconds(), batchSize);
			total += deleted;
			if (deleted < batchSize) {
				break;
			}
		}
		if (total > 0) {
			pruned.increment(total);
			log.info("Deleted {} finished notice(s) about things more than {} day(s) ago", total, retention.toDays());
		}
		return total;
	}
}
