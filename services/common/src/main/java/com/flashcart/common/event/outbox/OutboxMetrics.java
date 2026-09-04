package com.flashcart.common.event.outbox;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Makes a stalled relay visible.
 *
 * <p>This exists because of a specific failure. The relay is a scheduled job that swallows its own
 * exceptions on purpose — a scheduled method that throws stops being rescheduled by some executors,
 * and a relay that quietly stops means every saga in the platform halts with no error anywhere. That
 * is the right call, and it is exactly what makes the failure invisible: the service stays healthy,
 * the HTTP endpoints keep answering, the orders that were already in flight complete, and nothing
 * new ever reaches the bus.
 *
 * <p>Two numbers say whether that is happening, and neither is derivable from the other:
 *
 * <ul>
 * <li>{@code flashcart_outbox_unpublished} — how many messages are queued. A steady small number is
 *     normal under load; a number that only climbs is a relay that has stopped.
 * <li>{@code flashcart_outbox_oldest_age_seconds} — how long the oldest one has waited. This is the
 *     one that actually alerts. Depth alone cannot distinguish a busy relay from a dead one, because
 *     a busy relay under a flash sale legitimately has a deep queue; an old row is only ever wrong.
 * </ul>
 *
 * <p>Both are polled rather than incremented, because the truth is in the table. A counter tracked in
 * memory would reset on restart and drift from the rows it claims to describe — and the case worth
 * detecting is precisely the one where the process is running and wrong.
 */
public class OutboxMetrics {

	private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

	private static final String DEPTH = """
			select count(*) from outbox_messages where published_at is null
			""";

	/**
	 * Age of the oldest unpublished row, in seconds.
	 *
	 * <p>Zero when the table is drained, which is deliberately indistinguishable from "the oldest
	 * message arrived this instant". Both mean nothing is stuck, which is the only question this
	 * gauge is asked.
	 */
	private static final String OLDEST = """
			select coalesce(extract(epoch from now() - min(created_at)), 0)
			  from outbox_messages
			 where published_at is null
			""";

	private final JdbcTemplate jdbc;
	private final AtomicLong unpublished = new AtomicLong();
	private final AtomicLong oldestAgeSeconds = new AtomicLong();

	public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;

		Gauge.builder("flashcart.outbox.unpublished", unpublished, AtomicLong::get)
				.description("Messages committed to the outbox that the relay has not yet published")
				.register(registry);

		Gauge.builder("flashcart.outbox.oldest.age.seconds", oldestAgeSeconds, AtomicLong::get)
				.description("How long the oldest unpublished outbox message has been waiting")
				.baseUnit("seconds")
				.register(registry);
	}

	/**
	 * Two cheap indexed queries. The partial index on unpublished rows serves both, and the table is
	 * overwhelmingly published rows within moments of a sale ending, so this stays small.
	 */
	@Scheduled(fixedDelayString = "${flashcart.outbox.metrics.fixed-delay:PT5S}")
	public void sample() {
		try {
			Long depth = jdbc.queryForObject(DEPTH, Long.class);
			Double oldest = jdbc.queryForObject(OLDEST, Double.class);
			unpublished.set(depth == null ? 0 : depth);
			oldestAgeSeconds.set(oldest == null ? 0 : oldest.longValue());
		}
		catch (DataAccessException ex) {
			// Losing the sample is not worth failing anything over, but it must not be silent: a
			// gauge frozen at its last value looks exactly like a healthy queue.
			log.warn("Could not sample outbox metrics; the gauges are now stale", ex);
		}
	}

	/** Counts what the relay actually managed to send, as opposed to what it tried to. */
	static Counter published(MeterRegistry registry) {
		return Counter.builder("flashcart.outbox.published")
				.description("Messages the broker has acknowledged")
				.register(registry);
	}

	/**
	 * Send failures, counted per attempt rather than per message.
	 *
	 * <p>A single poisoned row retried every tick produces a fast-climbing rate here while depth
	 * stays at one — which is the signature worth recognising, and is not the same shape as a broker
	 * that is down.
	 */
	static Counter sendFailures(MeterRegistry registry) {
		return Counter.builder("flashcart.outbox.send.failures")
				.description("Relay attempts that the broker did not acknowledge")
				.register(registry);
	}
}
