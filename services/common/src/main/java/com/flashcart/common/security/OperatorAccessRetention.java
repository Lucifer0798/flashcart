package com.flashcart.common.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Expires operator access records, once somebody has said how long they should be kept.
 *
 * <p>ADR 0025 created {@code operator_access_log} and named this in the same breath: nothing expired
 * the rows, so the table grew with operator activity for ever. That is the correct default for an
 * audit trail and the wrong one for a disk.
 *
 * <h2>Why this is not the outbox's retention, despite looking like it</h2>
 *
 * <p>{@link com.flashcart.common.event.outbox.OutboxRetention} prunes rows that have <em>done their
 * job</em>: a published outbox message was acknowledged by the broker and nothing will read it
 * again. Deleting it loses history and nothing else, so a sensible default window is an easy call.
 *
 * <p>An audit row has no such moment. It is the only record that an access happened, and it is most
 * valuable long after it was written — nobody queries this table on a normal day. Deleting one is
 * therefore not cleanup, it is the destruction of evidence, and the two ways to get it wrong are not
 * symmetric: a full disk is visible, recoverable and embarrassing, while an access nobody can
 * reconstruct is indistinguishable from one that never happened.
 *
 * <h2>So: off unless asked</h2>
 *
 * <p>{@code flashcart.audit.retention} is unset by default and nothing is deleted. ADR 0025 said how
 * long the platform should be able to answer "who read my data" is a real decision; a library
 * default quietly deleting a year of audit history because nobody set a property would be that
 * decision made silently, by me, for every deployment.
 *
 * <p>The other failure is not silent either: when no retention is configured the service says so
 * once at startup, with the row count, so unbounded growth is a thing somebody chose to leave rather
 * than a thing nobody noticed.
 *
 * <p>And a sweep that deletes anything logs how many and how old, because deleting audit records is
 * itself an event worth being able to find afterwards.
 */
public class OperatorAccessRetention {

	private static final Logger log = LoggerFactory.getLogger(OperatorAccessRetention.class);

	/**
	 * Batched, for the reason the outbox is: the delete is cheap, but this is a table the read path
	 * writes to, and one enormous lock on a first run against a long-neglected table would stall
	 * every operator read at once.
	 */
	private static final String PRUNE = """
			delete from operator_access_log
			 where id in (
			       select id from operator_access_log
			        where read_at < now() - make_interval(secs => ?)
			        limit ?)
			""";

	private final JdbcTemplate jdbc;
	private final Duration retention;
	private final int batchSize;

	/** @param retention null or zero to keep everything, which is the default. */
	public OperatorAccessRetention(JdbcTemplate jdbc, Duration retention, int batchSize) {
		this.jdbc = jdbc;
		this.retention = retention;
		this.batchSize = batchSize;
	}

	@Scheduled(
			fixedDelayString = "${flashcart.audit.retention-fixed-delay:PT6H}",
			initialDelayString = "${flashcart.audit.retention-initial-delay:PT5M}")
	public void prune() {
		try {
			sweep();
		}
		catch (DataAccessException ex) {
			// Same reasoning as the outbox's sweeper: a scheduled method that throws may stop being
			// rescheduled, and a retention job that quietly stops is a disk that fills up months
			// later with no trace of when it began.
			log.error("Audit retention sweep failed; will retry on the next tick", ex);
		}
	}

	/**
	 * @return how many records were destroyed; zero when retention is not configured, which is not
	 *         an error and is the default.
	 */
	public int sweep() {
		if (!configured()) {
			// Deliberately no query at all. The "it will grow" message is said once at startup
			// rather than every six hours, because a warning on a timer is one people stop reading.
			return 0;
		}
		int deleted = jdbc.update(PRUNE, retention.toSeconds(), batchSize);
		if (deleted > 0) {
			log.info("Deleted {} operator access record(s) older than {}", deleted, humanReadable());
		}
		return deleted;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void reportOnStartup() {
		if (configured()) {
			log.info("Operator access records are kept for {}", humanReadable());
			return;
		}
		try {
			Long rows = jdbc.queryForObject("select count(*) from operator_access_log", Long.class);
			log.info("""
					No audit retention configured (flashcart.audit.retention), so operator access \
					records are kept for ever; operator_access_log holds {} row(s). That is the \
					safe default for an audit trail and an unbounded one for a disk.""", rows);
		}
		catch (DataAccessException ex) {
			log.warn("Could not read the size of operator_access_log", ex);
		}
	}

	/**
	 * {@code Duration.toString()} renders a year as {@code PT8760H}, which is accurate and is not
	 * what somebody scanning a log wants to read.
	 */
	private String humanReadable() {
		long days = retention.toDays();
		return days >= 1 ? days + " day(s)" : retention.toString();
	}

	private boolean configured() {
		return retention != null && !retention.isZero() && !retention.isNegative();
	}
}
