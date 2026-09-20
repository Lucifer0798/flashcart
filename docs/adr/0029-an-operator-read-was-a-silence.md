# 0029 — An operator read was a silence

**Status:** Accepted · **Date:** 2026-09-20 · **Phase:** post-roadmap

## Context

[ADR 0018](0018-metrics-answer-questions-the-logs-cannot.md) set out what the metrics in this
platform are for:

> The metrics written by hand are not a survey of the system; they are a list of its **silences**.

By that standard the audit trail was unfinished. [ADR 0025](0025-record-what-an-operator-reads.md)
made an operator's read of somebody else's data *recorded*; it did not make it *visible*. The row
went into `operator_access_log` and a line went into the service log, and nothing else changed
anywhere. Nobody queries that table on an ordinary day — the ADR says so itself — so nothing would
show that operator reads had happened today, or that there were suddenly forty times as many as
usual.

Every other consequential thing here is metered: gate decisions, outbox depth and age, send failures,
saga transitions and the ones the state machine declined. The one action that touches a customer's
privacy was not. [ADR 0028](0028-looking-is-not-acting.md) then extended it to orders, so the
unmetered surface grew.

## Decision

**Count the reads, by action, and count the refusals separately.**

`flashcart_operator_reads_total{action}` counts an operator reading another customer's row.
`flashcart_operator_read_record_failures_total` counts a read refused because the access could not be
written down.

**Per action, not in total.** "Operator reads happened" is not a useful number. Somebody opening one
payment and somebody listing every order a customer ever placed are different events, and the action
is already stored precisely so they can be told apart — a single counter would throw that away at the
point where it matters most.

**A customer reading their own is still not counted**, for the reason it is not recorded: it is the
ordinary path, and including it would bury the handful of reviewable events under all the traffic
nobody needs to look at. The metric and the table agree about what an event is, which is what makes
one a usable index into the other.

### Only one of these alerts, and it is not the interesting one

ADR 0018's rule is that an alert describes a state which is **wrong on its own terms**, never a
metric being high. "Operators read a lot today" is a business fact. It might be a sale, a support
backlog, or somebody working through a list they should not have — and the platform cannot tell
which, so an alert on it would be a guess dressed as a fault. It gets a dashboard panel and no
threshold.

The refusal counter is different, and is wrong on its own terms: it can only be non-zero when
`operator_access_log` is unwritable, which means the service is refusing every operator read of
another customer's data rather than serving one unrecorded. That is ADR 0025's property holding
exactly as designed — and from outside it looks like an unexplained rise in 500s on a handful of
endpoints, which is the least informative thing it could possibly look like. `OperatorReadsUnrecordable`
names it.

### Counted in the writer, not in a decorator

The availability gate is metered by a decorator, for a reason ADR 0018 gives: the *disabled* gate has
to be measured identically or the comparison means nothing. There is no disabled audit log to compare
against, so the same machinery here would be shape without purpose. The counters live in
`OperatorAccessLog`, which is the one place every recorded access already passes through.

The registry is optional in the way the outbox treats it — a service without one still records the
access and simply has no counter — so metrics cannot become a reason an access fails to be written.

## Alternatives considered

**Build the audit reader endpoint instead.** The other obvious next step, and it answers a different
question: *who read my data* rather than *is this happening, and how much*. It also carries an
authorisation decision worth making deliberately — who may read the log, and whether an operator may
read it about themselves — and bundling that into a change about visibility would have settled it in
passing. Still worth doing; still not this.

**Alert on the read rate anyway, with a generous threshold.** Rejected on ADR 0018's rule. A
threshold nobody can justify becomes a threshold somebody raises, and then an alert nobody believes.

**Derive the metric from the table by polling, the way the outbox gauges do.** Right for the outbox,
because there the truth is in the table and an in-memory counter would drift from the rows it claims
to describe. Here the counter and the row are written in the same call, so the only thing polling
would add is a query per scrape against a table that exists to be written and rarely read.

## Consequences

**Good.** Operator access is visible in Grafana beside everything else that matters, and a platform
refusing reads because it cannot record them now says so rather than emitting 500s.

**Counters reset on restart, and this one is not the audit trail.** The table is. The metric answers
"what is happening now"; the row answers "what happened then". Anyone reaching for the counter to
answer a question about the past should be reaching for the table, and the panel description says so.

**Still open.** The audit table has no reader, and now lives in three databases. Per-service ports
remain published. The publisher still stores a hard-coded sampled flag. And an operator still cannot
*cancel* another customer's order, which ADR 0028 left deliberately undecided.
