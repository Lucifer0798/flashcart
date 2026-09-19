# 0027 — The outbox hop is a span

**Status:** Accepted · **Date:** 2026-09-15 · **Phase:** post-roadmap · *a gap recorded here was later closed by [ADR 0028](0028-looking-is-not-acting.md)*

## Context

[ADR 0020](0020-a-trace-must-survive-the-outbox.md) made a buyer's trace survive the outbox: the
publisher stores a `traceparent` with the row, and the relay replays it onto the wire so the consumer
continues the same trace rather than starting a new one. It also recorded what it had not done:

> **Bad.** The relay's send has no span of its own, so the outbox hop shows as a gap between the queue
> write and the consumer. That gap is real — the message genuinely was not moving — but it is not
> *labelled*, and someone reading a trace for the first time will wonder what happened in it.

That has stood since Phase 11, and it is the oldest outstanding item in this repository. It is also
the only one left that is not about authorisation, which is its own argument for doing it: six
consecutive changes had been security work, each named by the last, and a chain that picks its own
successor will happily run past everything else.

The practical cost is small but real. A trace showed a producing span, then nothing, then a consumer
span some hundreds of milliseconds later. Nothing said whether the relay was slow, the broker was
slow, or the relay had stopped entirely — and those are the three things somebody looking at that
gap is trying to tell apart.

## Decision

**The relay opens a real span around the hop, and the consumer parents on it.**

`OutboxRelay` starts a `PRODUCER` span named `outbox relay <topic>`, as a child of the stored context,
and the outgoing `traceparent` is built from *that* span. The chain becomes
`buyer → outbox relay → consumer` rather than `buyer → consumer` with the relay invisible. The trace
id is unchanged, because the hop is inside the trace it describes; only the consumer's parent moves,
onto the span that actually handed the message to the broker.

**The span starts when the row was queued, not when the relay picked it up.** This is the part worth
arguing about, because most of that duration is time the relay spent doing nothing at all.

It is right because the span represents *the hop* — committed row to acknowledged broker write — and
that interval is precisely what the reader is asking about. A span covering only the send would sit
beside the gap rather than explaining it, which is where this already was.
`flashcart.outbox.queued_ms` separates the waiting from the sending for anyone who needs it, and in
practice the wait dominates: a measured hop was 709 ms of which 698 ms was queue time. That is the
answer to "was the relay slow?" — no, it was asleep, and the poll interval is the thing to change.

**Injected, not global.** The tracer comes from the `OpenTelemetry` bean rather than
`GlobalOpenTelemetry`, which Spring Boot does not reliably register. The global would have returned a
no-op tracer, every hop would have been a non-recording span, and the whole change would have
compiled, passed review and done nothing — the exact failure this repository keeps finding. A service
without tracing gets `OpenTelemetry.noop()` explicitly, and the relay still sends.

## Alternatives considered

**A span covering only the send.** Conventional, immune to clock skew, and it leaves the gap exactly
as unexplained as before with a small labelled span next to it. It answers "how long did the send
take" — which was never the question.

**Two spans: one for the wait, one for the send.** Strictly more accurate and more machinery than the
problem justifies. The wait is not an operation anybody performed; it is the absence of one, which is
what a gap between two spans already conveys once something bounds it.

**Leave the consumer parented on the buyer's span.** Smaller change, and it produces a tree where the
relay hop and the consumer are siblings — so the causal chain still is not visible, which was the
complaint. Re-parenting is safe precisely because the hop is in the same trace.

**Enable producer instrumentation on the relay's `KafkaTemplate`.** The obvious answer and a
regression: ADR 0020 turned it off because it injects whatever context the relay thread is in — its
own scheduled tick — over the buyer's replayed header. That would restore the disease this ADR's
predecessor cured.

## Consequences

**Good.** The hop is visible, attributed, and carries `attempts`, so a message being retried shows as
repeated hops rather than one long silence. A failed send now records the exception on the span and
sets its status, so a stalled saga is findable in the trace as well as in the log.

**A bug this introduced, caught before merge.** Building the header from the hop meant adding it
unconditionally, and the value is null when a service has no tracing *and* the row was queued without
a context. That dereferenced null, failed the send, and would have retried it for ever — stalling a
saga over tracing metadata. Guarded, and there is a test named for it.

**Clock skew is now visible where it was not.** The start timestamp comes from the database clock and
the end from the JVM's, so a skewed pair renders a hop slightly long or short. On one host that is
noise; anywhere the two clocks genuinely differ it would want a monotonic reference. Worth knowing
before believing a hop duration to the millisecond.

**The publisher still hard-codes its sampled flag to `01`.** The wire is now honest — the outgoing
header carries the hop's real flags — but the *stored* value does not, so a relay resuming a context
still treats it as sampled. Fixing that means the publisher reading a live `SpanContext` rather than
the MDC, which would give `flashcart-common` a hard dependency on OpenTelemetry where today it has an
optional one. That is a trade worth making deliberately, and this is not the record that makes it.

**Still open.** Per-service ports remain published, so the gateway is an optimisation rather than a
boundary. An operator cannot read another customer's order. And the audit table added in
[ADR 0025](0025-record-what-an-operator-reads.md) has no reader.

*The order half was since closed by [ADR 0028](0028-looking-is-not-acting.md), which opened the reads
and left cancel closed. The other two still stand.*
