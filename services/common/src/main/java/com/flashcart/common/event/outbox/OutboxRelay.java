package com.flashcart.common.event.outbox;

import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.flashcart.common.web.CorrelationId;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import io.micrometer.core.instrument.Counter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Moves committed outbox rows onto Kafka.
 *
 * <h2>Why the payload goes out as a string</h2>
 *
 * The row already holds the exact JSON the normal publisher would have produced, so the relay sends
 * those bytes verbatim through a string serializer. Deserialising it back into an object only to
 * re-serialise it would risk the two encodings differing — and consumers would then be reading a
 * subtly different wire format depending on which path a message happened to take.
 *
 * <h2>Ordering</h2>
 *
 * Rows are taken in insertion order and Kafka keys by aggregate id, so messages about one order stay
 * in sequence. {@code FOR UPDATE SKIP LOCKED} lets several instances relay at once without
 * duplicating work or queueing on the same rows — but note it also means two instances can interleave
 * <em>across</em> aggregates. That is fine: ordering is only ever promised within one.
 *
 * <h2>What happens on failure</h2>
 *
 * A row that cannot be published stays unpublished and is retried on the next tick, with its attempt
 * count incremented. Nothing is dropped. A row whose attempts keep climbing is a genuine operational
 * signal — it is logged loudly, because a message stuck in the outbox is a saga that has silently
 * stopped.
 */
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final String CLAIM = """
			select id, topic, message_key, event_id, event_type, correlation_id, trace_parent,
			       payload::text, attempts, created_at
			  from outbox_messages
			 where published_at is null
			 order by created_at, id
			 limit ?
			   for update skip locked
			""";

	private static final String MARK_PUBLISHED =
			"update outbox_messages set published_at = now() where id = ?";

	private static final String RECORD_FAILURE =
			"update outbox_messages set attempts = attempts + 1, last_error = ? where id = ?";

	private final JdbcTemplate jdbc;
	private final KafkaTemplate<String, String> kafka;
	private final TransactionTemplate transactions;
	private final int batchSize;
	private final Counter published;
	private final Counter sendFailures;
	private final Tracer tracer;

	public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
			PlatformTransactionManager transactionManager, MeterRegistry registry, int batchSize,
			OpenTelemetry openTelemetry) {
		// Injected rather than taken from GlobalOpenTelemetry, which Spring Boot does not reliably
		// register: the global would hand back a no-op tracer, every hop would be a non-recording
		// span, and the whole thing would quietly do nothing while compiling perfectly.
		this.tracer = openTelemetry.getTracer("flashcart-outbox");
		this.jdbc = jdbc;
		this.kafka = kafka;
		this.published = OutboxMetrics.published(registry);
		this.sendFailures = OutboxMetrics.sendFailures(registry);
		// A TransactionTemplate rather than @Transactional: relayBatch is called from relay() on
		// this same object, and self-invocation does not pass through the proxy, so the annotation
		// would silently do nothing. That is not a cosmetic difference here — without a surrounding
		// transaction the FOR UPDATE SKIP LOCKED below takes its locks and drops them the instant the
		// query returns, and two instances would happily claim the same rows.
		this.transactions = new TransactionTemplate(transactionManager);
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${flashcart.outbox.relay.fixed-delay:PT1S}",
			initialDelayString = "${flashcart.outbox.relay.initial-delay:PT5S}")
	public void relay() {
		try {
			int published = relayBatch();
			if (published > 0) {
				log.debug("Relayed {} outbox message(s)", published);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws stops being rescheduled by some executors, and a relay
			// that quietly stops means every saga in the system halts with no error anywhere.
			//
			// Swallowing it is what keeps the relay alive, and also what makes the failure silent:
			// the service stays healthy while nothing new reaches the bus. OutboxMetrics is the
			// counterweight — the queue depth and the age of its oldest row are what actually
			// surface this.
			log.error("Outbox relay failed; will retry on the next tick", ex);
		}
	}

	/** One batch, exposed so tests can drive it without waiting on a timer. */
	public int relayBatch() {
		return transactions.execute(status -> claimAndSend());
	}

	private int claimAndSend() {
		List<Pending> pending = jdbc.query(CLAIM,
				(rs, rowNum) -> new Pending(
						rs.getObject("id", UUID.class),
						rs.getString("topic"),
						rs.getString("message_key"),
						rs.getString("event_id"),
						rs.getString("event_type"),
						rs.getString("correlation_id"),
						rs.getString("trace_parent"),
						rs.getString("payload"),
						rs.getInt("attempts"),
						rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
				batchSize);

		int sent = 0;
		for (Pending message : pending) {
			if (send(message)) {
				jdbc.update(MARK_PUBLISHED, message.id());
				sent++;
				this.published.increment();
			}
		}
		return sent;
	}

	private boolean send(Pending message) {
		ProducerRecord<String, String> record =
				new ProducerRecord<>(message.topic(), message.key(), message.payload());
		record.headers().add(new RecordHeader("eventType",
				message.eventType().getBytes(StandardCharsets.UTF_8)));
		record.headers().add(new RecordHeader("eventId",
				message.eventId().getBytes(StandardCharsets.UTF_8)));
		if (message.correlationId() != null) {
			record.headers().add(new RecordHeader(CorrelationId.HEADER,
					message.correlationId().getBytes(StandardCharsets.UTF_8)));
		}
		// The relay's template is deliberately not observation-enabled, so nothing injects a
		// traceparent behind our back. Producer instrumentation would use whatever context the relay
		// thread is in -- its own scheduled-task trace -- and the trace that matters belongs to a
		// request that finished minutes ago, possibly in a previous process.
		try (Scope queuedScope = traceScope(message.traceParent())) {
			Span hop = startHop(message);
			try (Scope hopScope = hop.makeCurrent()) {
				// Null when this service has no tracing configured and the row was queued without a
				// context either. Adding the header anyway would have dereferenced null and failed
				// the send -- retried for ever, over tracing metadata, which would be an absurd way
				// to stall a saga.
				String traceparent = outgoingTraceParent(hop, message.traceParent());
				if (traceparent != null) {
					record.headers().add(new RecordHeader("traceparent",
							traceparent.getBytes(StandardCharsets.UTF_8)));
				}

				// Awaited deliberately, unlike the direct publisher: the row must only be marked
				// published once the broker has actually acknowledged it. Marking optimistically
				// would reintroduce exactly the loss the outbox exists to prevent.
				kafka.send(record).get();
				return true;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				hop.setStatus(StatusCode.ERROR, "interrupted");
				return false;
			}
			catch (Exception ex) {
				sendFailures.increment();
				hop.setStatus(StatusCode.ERROR, String.valueOf(ex.getMessage()));
				hop.recordException(ex);
				jdbc.update(RECORD_FAILURE, ex.getMessage(), message.id());
				if (message.attempts() > 0 && message.attempts() % 10 == 0) {
					log.error("Outbox message {} has failed {} times; a saga is stalled behind it",
							message.eventId(), message.attempts(), ex);
				}
				return false;
			}
			finally {
				hop.end();
			}
		}
	}

	/**
	 * The span that fills the hole in the trace.
	 *
	 * <p>ADR 0020 shipped with the outbox hop showing as an unexplained gap between the queue write
	 * and the consumer: the message genuinely was not moving, but nothing said so, and a reader could
	 * not tell a slow relay from a stalled one.
	 *
	 * <p><strong>It starts when the row was queued, not when the relay picked it up.</strong> That is
	 * the decision worth arguing about, because most of that span is time the relay spent doing
	 * nothing at all. It is right because the span represents <em>the hop</em> — the message's
	 * journey from committed row to acknowledged broker write — and that is the interval the reader
	 * is asking about. A span covering only the send would sit next to the gap rather than explaining
	 * it, which is where this already was. {@code flashcart.outbox.queued_ms} separates the waiting
	 * from the sending for anyone who needs the distinction.
	 *
	 * <p>The start timestamp comes from the database clock and the end from this JVM's, so a skewed
	 * pair can render a hop slightly longer or shorter than it was. On one host that is noise; it
	 * would be worth carrying a monotonic reference anywhere the two clocks are genuinely apart.
	 */
	private Span startHop(Pending message) {
		return tracer.spanBuilder("outbox relay " + message.topic())
				.setSpanKind(SpanKind.PRODUCER)
				.setStartTimestamp(message.queuedAt())
				.setAttribute("messaging.system", "kafka")
				.setAttribute("messaging.destination.name", message.topic())
				.setAttribute("messaging.message.id", message.eventId())
				.setAttribute("flashcart.event.type", message.eventType())
				.setAttribute("flashcart.outbox.attempts", message.attempts())
				.setAttribute("flashcart.outbox.queued_ms",
						Duration.between(message.queuedAt(), Instant.now()).toMillis())
				.startSpan();
	}

	/**
	 * What the consumer reads, and therefore what it parents itself on.
	 *
	 * <p>The hop's own context when there is one, so the chain is buyer to relay to consumer rather
	 * than buyer to consumer with the relay off to one side. The trace id is unchanged either way —
	 * the hop is a child of the stored context — so this moves the consumer under the span that
	 * actually handed the message to the broker, which is where it belongs.
	 *
	 * <p>It also carries the real sampled flag rather than the {@code 01} the publisher stores, since
	 * it is built from a live {@code SpanContext}. The stored value keeps its hard-coded flag; that
	 * is the publisher's decision to revisit, recorded in ADR 0020 and unchanged here.
	 *
	 * <p>Falls back to the stored string when tracing is not configured, where the hop is a non
	 * recording span with an invalid context and building a header from it would produce an all-zero
	 * traceparent that breaks the very continuation this exists to protect.
	 */
	static String outgoingTraceParent(Span hop, String storedTraceParent) {
		SpanContext context = hop.getSpanContext();
		if (!context.isValid()) {
			return storedTraceParent;
		}
		return "00-" + context.getTraceId() + "-" + context.getSpanId()
				+ "-" + context.getTraceFlags().asHex();
	}


	/**
	 * Re-enters the trace recorded when this message was queued.
	 *
	 * <p>Returns a no-op scope when there is no stored context, when the string is malformed, or when
	 * OpenTelemetry is not on the classpath at all -- in each case the relay still sends, and only
	 * the trace is poorer for it. Failing a message because its tracing metadata is unreadable would
	 * be a spectacularly bad trade.
	 */
	private static Scope traceScope(String traceParent) {
		if (traceParent == null) {
			return Scope.noop();
		}
		String[] parts = traceParent.split("-");
		if (parts.length != 4) {
			return Scope.noop();
		}
		try {
			SpanContext parent = SpanContext.createFromRemoteParent(parts[1], parts[2],
					TraceFlags.fromHex(parts[3], 0), TraceState.getDefault());
			if (!parent.isValid()) {
				return Scope.noop();
			}
			return Context.current().with(Span.wrap(parent)).makeCurrent();
		}
		catch (RuntimeException ex) {
			log.debug("Unreadable traceparent {}; sending untraced", traceParent);
			return Scope.noop();
		}
	}

	private record Pending(UUID id, String topic, String key, String eventId, String eventType,
			String correlationId, String traceParent, String payload, int attempts, Instant queuedAt) {
	}
}
