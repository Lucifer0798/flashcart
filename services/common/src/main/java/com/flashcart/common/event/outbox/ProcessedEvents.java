package com.flashcart.common.event.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records which messages a consumer has already applied.
 *
 * <h2>What this replaces</h2>
 *
 * Phase 5 deduplicated by asking the state machine whether a transition was legal and ignoring it if
 * not. That worked, and it inferred "already processed" from "not currently legal" — two statements
 * that are usually the same and are not the same thing. A genuinely impossible transition, meaning a
 * real bug, was silently ignored exactly like a harmless duplicate.
 *
 * <p>This records the fact instead of inferring it.
 *
 * <h2>Why it must be in the handler's transaction</h2>
 *
 * {@link #claim} inserts a row. Called inside the same transaction as the handler's work, the claim
 * and the work commit together or not at all — so a handler that fails halfway does not leave the
 * message marked as processed, and a handler that succeeds cannot have its claim lost.
 *
 * <p>Recording it separately, before or after, reopens the same two-systems gap the outbox exists to
 * close, on the consuming side.
 *
 * <h2>Per consumer, not per message</h2>
 *
 * The key is (event id, consumer). Several services legitimately consume the same event and each
 * must process it once — a single "seen" flag would let whichever consumer arrived first suppress it
 * for everyone else.
 */
public class ProcessedEvents {

	private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);

	private static final String CLAIM = """
			insert into processed_events (event_id, consumer, processed_at)
			values (?, ?, now())
			on conflict (event_id, consumer) do nothing
			""";

	private final JdbcTemplate jdbc;

	public ProcessedEvents(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Take ownership of this message for this consumer.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} makes the check and the record one statement, so two
	 * concurrent deliveries of the same message cannot both win — the same reasoning as every other
	 * conditional write in this codebase.
	 *
	 * @return true if this consumer has not applied the message before and should now do so; false
	 *         if it is a duplicate and must be skipped
	 */
	public boolean claim(String eventId, String consumer) {
		boolean claimed = jdbc.update(CLAIM, eventId, consumer) > 0;
		if (!claimed) {
			log.debug("Skipping {} for {}: already processed", eventId, consumer);
		}
		return claimed;
	}
}
