package com.flashcart.common.event.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the consumer reads out of the wire, which decides where it hangs in the trace.
 *
 * <p>Small surface, disproportionate consequences: get it wrong in one direction and every consumer
 * span detaches into a trace of its own, which is the failure ADR 0020 existed to prevent in the
 * first place. Get it wrong in the other and the relay sends nothing at all, because the header
 * value was null.
 */
class OutboxTraceParentTest {

	private static final String STORED = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

	private static Span recordingSpan(String traceId, String spanId, TraceFlags flags) {
		return Span.wrap(SpanContext.create(traceId, spanId, flags, TraceState.getDefault()));
	}

	@Test
	@DisplayName("with a live hop the consumer parents on the relay, not on the span that queued it")
	void prefersTheHopsOwnContext() {
		Span hop = recordingSpan("4bf92f3577b34da6a3ce929d0e0e4736", "aa00bb11cc22dd33",
				TraceFlags.getSampled());

		String header = OutboxRelay.outgoingTraceParent(hop, STORED);

		assertThat(header).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-aa00bb11cc22dd33-01");
	}

	@Test
	@DisplayName("and stays in the same trace, which is what makes the re-parenting safe")
	void keepsTheTraceId() {
		Span hop = recordingSpan("4bf92f3577b34da6a3ce929d0e0e4736", "aa00bb11cc22dd33",
				TraceFlags.getSampled());

		assertThat(OutboxRelay.outgoingTraceParent(hop, STORED))
				.contains("4bf92f3577b34da6a3ce929d0e0e4736");
	}

	@Test
	@DisplayName("the sampled flag is the hop's real one, not the publisher's hard-coded 01")
	void carriesTheRealSampledFlag() {
		Span unsampled = recordingSpan("4bf92f3577b34da6a3ce929d0e0e4736", "aa00bb11cc22dd33",
				TraceFlags.getDefault());

		assertThat(OutboxRelay.outgoingTraceParent(unsampled, STORED)).endsWith("-00");
	}

	@Test
	@DisplayName("without tracing configured the stored context is replayed unchanged")
	void fallsBackToTheStoredContext() {
		// What an unconfigured OpenTelemetry hands back: a span whose context is not valid.
		assertThat(OutboxRelay.outgoingTraceParent(Span.getInvalid(), STORED)).isEqualTo(STORED);
	}

	@Test
	@DisplayName("and with neither, there is no header rather than a null one")
	void noContextAtAllIsNull() {
		// The relay checks for this. Before it did, the send dereferenced null and the message was
		// retried for ever over tracing metadata.
		assertThat(OutboxRelay.outgoingTraceParent(Span.getInvalid(), null)).isNull();
	}
}
