# 0018 — Metrics exist to answer the questions logs cannot

**Status:** Accepted · **Date:** 2026-09-03 · **Phase:** 9

## Context

Phase 8 shipped a bug that no test caught and no log line reported: a refusal dead-lettered instead of
publishing, and the order sat in `CREATED` for ever. What made it invisible was not a missing log —
the log was there — but that nothing was *counting*. A stalled saga looks identical to a quiet one.

That is the shape of every observability problem in this platform, and it is worth naming precisely:
**the dangerous failures here are the silent ones, and they are silent by design.**

- The outbox relay swallows its own exceptions, because a scheduled method that throws stops being
  rescheduled and a dead relay halts every saga. Swallowing is correct, and it is exactly what makes
  the failure invisible.
- The availability gate degrades to `UNKNOWN` when Redis is unreachable, and the platform stays
  completely correct while doing it. Degrading quietly is the gate's whole design.
- The reservation sweeper ran for six phases without once completing (ADR 0017's commit message has
  the detail). Nothing alerted, because nothing counted sweeps.

Every one of those services reports `UP`. Health checks answer "is the process alive", which was never
the question.

## Decision

Micrometer with a Prometheus registry in every service, scraped by Prometheus, displayed by Grafana,
with dashboards and alert rules provisioned from the repository.

**Instrument the silences, not the traffic.** HTTP and JVM metrics come free with Actuator and are
worth having, but nothing was hand-written for them. Every metric added by hand exists because some
specific failure would otherwise be undetectable:

| Metric | The silence it breaks |
|---|---|
| `flashcart_outbox_oldest_age_seconds` | A relay that stopped. Depth cannot say this — under a flash sale a deep queue is correct; an *old* row never is |
| `flashcart_outbox_unpublished` | Queue depth, for shape. Meaningless alone, necessary alongside the age |
| `flashcart_outbox_send_failures_total` | One poisoned row retried for ever, which climbs here while depth stays at one |
| `flashcart_gate_decisions_total{decision}` | `refused` is the gate's entire payoff; a sustained `unknown` rate means Redis is gone and the gate is doing nothing at all |
| `flashcart_saga_transitions_total{from,to}` | Where orders actually end up, including which compensation is firing |
| `flashcart_saga_transitions_declined_total` | Transitions that genuinely should not have been attempted |

**Gauges are polled from the tables, not tracked in memory.** The outbox gauges run two indexed
queries every five seconds rather than incrementing a counter on write. An in-memory count resets on
restart and drifts from the rows it claims to describe — and the case worth detecting is precisely the
one where the process is running and wrong.

**The gate is instrumented by a decorator**, so the disabled gate is measured identically. A run with
the gate off is the control, and a control you cannot compare against is not a control.

**Alert on states that are wrong on their own terms.** Every rule in `rules.yml` describes something
that is never correct — an outbox row two minutes old, a steady `UNKNOWN` rate, a declined transition
after Phase 8 made duplicates impossible here. No rule fires on a metric merely being high, because a
threshold nobody can justify is a threshold that gets silenced.

## Alternatives considered

**Distributed tracing (Micrometer Tracing + Tempo/Zipkin).** The obvious fit for a saga crossing four
services over Kafka, and genuinely the better tool for "where did this one checkout go". Deferred, not
rejected: correlation IDs already stitch a checkout together in the logs, and tracing earns its
containers once Phase 11 is injecting failures worth tracing. Two containers here, not four, because
memory pressure on this machine has already turned one test run into a nine-hour one.

**A Kafka exporter for dead-letter depth.** Wanted — the DLQ is the one important signal not visible
in application metrics, and today it was found to have been unmonitored for six phases because CI was
calling a class removed in Kafka 4. Left out because it is a third container measuring a broker rather
than this platform, and CI now asserts the DLQ is empty with a call that actually works. It belongs
with the operational work in Phase 10.

**A common `application` tag on every meter.** Redundant: Prometheus already labels by scrape `job`
and `service`. Two names for one thing is how dashboards end up querying the wrong one.

**Grafana dashboards edited in the UI.** Rejected. `allowUiUpdates: false` and read-only mounts, so a
dashboard that is not in the repository does not exist — an edit lost on the next `compose down -v` is
the worst of both worlds.

## Consequences

**Good.** The three failures above are now each a number, and each has an alert that describes what
went wrong rather than which threshold was crossed. CI asserts the endpoints are not merely present
but actually scraped, and that the domain counters carry real values after a real checkout — which is
the difference between "configured" and "working", and precisely the gap that let `prometheus` sit in
every service's actuator exposure for eight phases while the endpoint returned 404.

**Bad.** Two more containers on a machine that is already tight, and Prometheus retains six hours
because nothing here is worth keeping overnight. The saga transition counter is tagged `from` and `to`,
which is bounded by the state machine but is genuinely cardinality that would need watching if the
status enum ever grew large.

There is also no tracing, so "why was *this* order slow" remains a question the logs answer by hand,
via the correlation id. That is a real gap and an accepted one until Phase 11.
