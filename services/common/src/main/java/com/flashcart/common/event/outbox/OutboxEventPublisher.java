package com.flashcart.common.event.outbox;

import java.util.UUID;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes messages to a database table instead of sending them to Kafka.
 *
 * <h2>The gap this closes</h2>
 *
 * Until now every service committed its state change and then published. Those are two separate
 * systems and there is a window between them:
 *
 * <ul>
 *   <li>the process dies after the commit and before the publish — the order is {@code RESERVED} and
 *       nothing was ever told, so the saga stops dead and the stock sits held until it expires;</li>
 *   <li>or the publish is attempted first and the transaction then rolls back — now inventory has
 *       been told to reserve stock for an order that does not exist.</li>
 * </ul>
 *
 * <p>No ordering of the two fixes it, because the failure is that they are two operations and only
 * one of them is transactional.
 *
 * <h2>How the outbox closes it</h2>
 *
 * The message is written to {@code outbox_messages} <em>in the caller's own transaction</em>, so it
 * commits or rolls back with the state change it describes — one operation, atomically. A separate
 * relay then moves rows to Kafka.
 *
 * <p>This converts the problem from "might be lost" to "will be delivered at least once, possibly
 * more" — which is the guarantee the consumers were already built for, and now the {@link
 * com.flashcart.common.event.outbox.ProcessedEvents} table makes that survivable rather than merely
 * survivable-in-practice.
 *
 * <p>Note there is no error handling here on purpose. A failure to write the outbox row <em>should</em>
 * fail the caller's transaction: the alternative is committing a state change whose event will never
 * exist, which is precisely the bug being fixed.
 */
public class OutboxEventPublisher implements EventPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

	private static final String INSERT = """
			insert into outbox_messages
			  (id, topic, message_key, event_id, event_type, correlation_id, payload, created_at)
			values (?, ?, ?, ?, ?, ?, ?::jsonb, now())
			on conflict (event_id) do nothing
			""";

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public OutboxEventPublisher(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public void publish(String topic, DomainEvent message) {
		// ON CONFLICT DO NOTHING on event_id: a caller that retries its own transaction should not
		// end up with the same message queued twice.
		jdbc.update(INSERT,
				UUID.randomUUID(),
				topic,
				message.aggregateId(),
				message.eventId(),
				message.eventType(),
				message.correlationId(),
				json.writeValueAsString(message));

		log.debug("Queued {} for {} to {}", message.eventType(), message.aggregateId(), topic);
	}
}
