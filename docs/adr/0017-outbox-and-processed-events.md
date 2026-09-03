# 0017 — A transactional outbox out, a processed-events table in

**Status:** Accepted · **Date:** 2026-09-03 · **Phase:** 8 · **Supersedes ADR 0014**

## Context

Two gaps had been left open deliberately, one at each end of the bus.

**Outbound.** A handler persisted its state change and then called `EventPublisher.publish`. Those are
two systems and one of them can fail alone. A crash between the commit and the send leaves an order
in `PAID` that nothing was ever told about — the saga stops, silently, and the only evidence is an
order that never ships. Publishing before the commit is worse: announce a fact, then fail to make it
true.

**Inbound.** ADR 0014 deduplicated by asking the state machine whether a transition was still legal.
That works, but it infers "already processed" from "not currently legal", and those are different
statements. A genuine bug is discarded exactly like a harmless duplicate.

## Decision

**Outbound: a transactional outbox.** `OutboxEventPublisher` writes the message into
`outbox_messages` using the caller's connection, inside the caller's transaction. The state change and
the intent to publish commit together or not at all. A separate scheduled `OutboxRelay` claims
unpublished rows with `SELECT ... FOR UPDATE SKIP LOCKED`, sends them, waits for the broker's
acknowledgement, and only then stamps `published_at`.

It is `@Primary`, so every existing caller switched to it without a line of domain code changing —
which is what `EventPublisher` was made an interface for in Phase 5.

The relay sends the stored JSON **verbatim** through a string serializer rather than deserialising and
re-serialising, so the bytes on the topic are the bytes that were committed.

**Inbound: a processed-events table.** `ProcessedEvents.claim` inserts `(event_id, consumer)` with
`ON CONFLICT DO NOTHING`; the insert either wins or does not, and the unique constraint decides the
race. `IdempotentHandler` runs the claim and the handler **in one transaction**, so work that rolls
back releases its claim and will be retried rather than being recorded as done.

The key is `(event_id, consumer)`, not `event_id` alone: several services legitimately handle the same
message, and a single "seen" flag would let whichever consumer arrived first suppress it for everyone.

## Alternatives considered

**Change data capture (Debezium) instead of a relay.** Strictly better on paper — no polling, no
`published_at` column. It is also a second piece of infrastructure to run and reason about, and the
polling relay is a few dozen lines. Worth revisiting if relay lag ever shows up in Phase 10.

**Kafka transactions across producer and database.** Covers Kafka-to-Kafka only. Every handler here
writes to PostgreSQL, so the guarantee stops at the boundary and an outbox is needed regardless.

**Keeping ADR 0014's state-machine check as the only defence.** Rejected for the reason 0014 itself
gave: it cannot distinguish a duplicate from a bug.

## Consequences

**Good.** No message can be lost between a commit and a send, in either direction. Duplicates are now
a recorded fact rather than an inference, so the state machine's guard goes back to doing only its own
job — rejecting genuinely illegal transitions — and its log line means something specific again. Both
tables are per-service, so this adds no coupling.

**Bad.** Publishing is now asynchronous, with the relay's poll interval as added latency on every
message. Ordering is preserved per aggregate by keying on it and by the relay reading in insertion
order, but the relay is a component that can fall behind, and `attempts`/`last_error` exist because it
will sometimes need explaining.

Both tables grow forever and neither has a cleanup policy yet. `processed_events` in particular cannot
be truncated casually — deleting a row makes that event processable again. Retention belongs with the
operational work in Phase 10; noting it here so it is a known debt rather than a discovery.

The state machine guard from ADR 0014 stays in place. It is now redundant on the duplicate path, which
is the point: two independent defences on a saga that moves money.
