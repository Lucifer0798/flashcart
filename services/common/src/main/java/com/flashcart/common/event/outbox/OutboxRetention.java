package com.flashcart.common.event.outbox;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Prunes the two tables Phase 8 added, which otherwise grow for ever.
 *
 * <p>ADR 0017 recorded this as known debt rather than discovering it later. A flash sale writes an
 * outbox row per message and a processed-events row per consumer per message, so the growth is
 * proportional to traffic and is fastest exactly when the platform is busiest.
 *
 * <h2>The two tables are not equally safe to prune, and the difference matters</h2>
 *
 * <p><strong>{@code outbox_messages}</strong> is easy. A row with {@code published_at} set has done
 * its job: the broker acknowledged it, and nothing reads it again. Deleting it loses history and
 * nothing else. Only published rows are ever touched — an unpublished row is a message still owed to
 * the bus, and deleting one would silently drop it.
 *
 * <p><strong>{@code processed_events}</strong> is not. Every row is the answer to "have I already
 * handled this?", so deleting one does not free space so much as <em>make that event processable
 * again</em>. If the broker then redelivers it — and Kafka's retention is the thing that decides
 * whether it can — the handler runs a second time. For a payment that means charging a customer
 * twice.
 *
 * <p>So the retention window here is not a disk-space decision. It must exceed the broker's own
 * retention, because a message that can no longer be redelivered cannot be double-processed, and one
 * that can, can. The default is seven days against Kafka's default seven, which is deliberately
 * <em>not</em> a comfortable margin — it is a number that should be raised the moment either side
 * changes, and it is written here rather than left implicit so that the next person changing Kafka's
 * retention has a chance of noticing.
 */
public class OutboxRetention {

	private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

	/**
	 * Published rows only. Batched, so a first run against a large table does not take one enormous
	 * lock — the delete is cheap but the table is exactly the one the relay is writing to.
	 */
	private static final String PRUNE_OUTBOX = """
			delete from outbox_messages
			 where id in (
			       select id from outbox_messages
			        where published_at is not null
			          and published_at < now() - make_interval(secs => ?)
			        limit ?)
			""";

	private static final String PRUNE_PROCESSED = """
			delete from processed_events
			 where event_id in (
			       select event_id from processed_events
			        where processed_at < now() - make_interval(secs => ?)
			        limit ?)
			""";

	private final JdbcTemplate jdbc;
	private final Duration outboxRetention;
	private final Duration processedRetention;
	private final int batchSize;

	public OutboxRetention(JdbcTemplate jdbc, Duration outboxRetention, Duration processedRetention,
			int batchSize) {
		this.jdbc = jdbc;
		this.outboxRetention = outboxRetention;
		this.processedRetention = processedRetention;
		this.batchSize = batchSize;
	}

	@Scheduled(
			fixedDelayString = "${flashcart.outbox.retention.fixed-delay:PT1H}",
			initialDelayString = "${flashcart.outbox.retention.initial-delay:PT5M}")
	public void prune() {
		try {
			int outbox = jdbc.update(PRUNE_OUTBOX, outboxRetention.toSeconds(), batchSize);
			int processed = jdbc.update(PRUNE_PROCESSED, processedRetention.toSeconds(), batchSize);
			if (outbox > 0 || processed > 0) {
				log.info("Pruned {} published outbox row(s) and {} processed-event row(s)",
						outbox, processed);
			}
		}
		catch (DataAccessException ex) {
			// Same reasoning as the relay: a scheduled method that throws may stop being
			// rescheduled, and a retention job that quietly stops is a disk that fills up months
			// later with no trace of when it began.
			log.error("Retention sweep failed; will retry on the next tick", ex);
		}
	}
}
