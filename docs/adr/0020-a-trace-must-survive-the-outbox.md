# 0020 — A trace has to survive the outbox, or it is not worth having

**Status:** Accepted · **Date:** 2026-09-07 · **Phase:** 11

## Context

[ADR 0018](0018-metrics-answer-questions-the-logs-cannot.md) deferred tracing to this phase on the
grounds that it "earns its containers once Phase 11 is injecting failures worth tracing". That is now
true, and the promise is kept here.

But adding a tracing backend to this platform is not the usual exercise, because of a decision made
two phases earlier. The outbox deliberately separates *deciding to publish* from *publishing*: the row
commits with the business change, and a relay sends it some time later, on a different thread, in a
different request, possibly after a restart. That separation is the entire point of
[ADR 0017](0017-outbox-and-processed-events.md) — and it is also precisely what cuts a distributed
trace in half.

Out of the box, a checkout traces as two unrelated fragments: the HTTP request that queued the
message, and a rootless relay span that sent it. Worse, the fragments are least informative exactly
when they matter most, because a saga that stalls mid-flight is the thing you most want to follow end
to end.

## Decision

**Capture the trace context at queue time and replay it at send time**, so one checkout is one trace
across every asynchronous hop.

`OutboxEventPublisher` records the current W3C `traceparent` into the `outbox_messages` row alongside
the payload — read from the MDC rather than by injecting a `Tracer`, so a service without tracing
simply stores a null. `OutboxRelay` writes that string back onto the outgoing record as a
`traceparent` header, and re-enters the stored context around the send.

**The relay's Kafka template is deliberately *not* observation-enabled.** This is the part that took
several attempts to get right, and it is counter-intuitive enough to be worth stating plainly:
Spring Kafka's producer instrumentation injects *the current* context into the outgoing headers, and
on a relay thread the current context is the relay's own scheduled tick. Enabling it overwrites the
buyer's `traceparent` with the sweeper's, and the consumer dutifully joins the wrong trace. The
instrumentation is not wrong; it simply has no way of knowing that the interesting context is one that
finished minutes ago in another process.

The cost is a missing span for the send itself. The benefit is that a checkout is a single trace
rather than two fragments, which was the whole reason for adding tracing.

**OpenTelemetry, not Brave.** Boot 4 ships `spring-boot-micrometer-tracing-opentelemetry` and has no
Brave equivalent.

## Alternatives considered

**Let the trace break, and stitch by correlation id.** The status quo before this phase, and workable:
the correlation id already travels on every message and appears in every log line. Rejected because it
puts the burden on a human with a log search at exactly the moment they are trying to understand a
stalled saga, and because a trace that stops at the interesting boundary trains people not to trust
traces.

**Trace the relay instead, and accept two traces linked by an attribute.** Cheaper, and genuinely
defensible for a high-volume system where re-entering context per message is not free. Rejected here
because this platform's traces are read by people trying to understand one order, not sampled in
aggregate.

**Store the whole span and continue it, rather than a remote parent.** A trace whose spans are minutes
apart with a gap in the middle is honest about what actually happened. Continuing an old span would
imply the work was contiguous when the message may have sat in the outbox through a restart.

## Consequences

**Good.** One checkout is one trace: 21 spans across five services, from the gateway through order,
inventory, payment and shipping and back, including the Redis `evalsha` where the availability gate
runs its Lua script. That is now the artefact for understanding any failure injected in this phase.

**Bad.** The relay's send has no span of its own, so the outbox hop shows as a gap between the queue
write and the consumer. That gap is real — the message genuinely was not moving — but it is not
*labelled*, and someone reading a trace for the first time will wonder what happened in it.

The `traceparent` is also stored with the sampled flag hard-coded to `01`. That is honest for a
platform sampling at 1.0, and would need to carry the real sampling decision anywhere that sampled
selectively, or the relay would resurrect traces the sampler had already dropped.

Finally, this adds a column to a hot table and a string parse per relayed message. Both are small, and
both are on the relay's path rather than the buyer's, which is the right side of that trade.
