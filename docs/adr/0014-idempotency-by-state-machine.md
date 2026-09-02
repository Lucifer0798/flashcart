# 0014 — Consumers are idempotent by consulting the state machine, not a dedup table

**Status:** Accepted · **Date:** 2026-09-02 · **Phase:** 5 · **Superseded in part by Phase 8**

## Context

At-least-once delivery is a certainty, not a risk. A consumer rebalance alone redelivers, and so does
any retry after a slow handler. Every consumer in this platform will see messages it has already
processed.

A consumer that applies a duplicate is broken. A consumer that *throws* on a duplicate is also
broken, and more subtly: the error handler retries it, the retries fail identically, and the message
is dead-lettered — so a message that was perfectly fine ends up quarantined, and the partition stalls
behind it.

## Decision

For now, the state machine is the deduplication mechanism. Every saga handler goes through
`OrderSaga.advance`, which checks `OrderStateMachine.canTransition` and **ignores** a transition that
is not legal from the order's current state.

A redelivered `InventoryReserved` finds the order already past `RESERVED`, `RESERVED → RESERVED` is
not an edge, and the message is acknowledged and dropped. The same reasoning covers inventory
(idempotent on the reservation key since Phase 3), payment (unique on its idempotency key) and
shipping (unique on order id).

## Alternatives considered

**A processed-events table, checked before handling.** The general answer, and it is Phase 8's job.
Deliberately not done now: it is a real piece of infrastructure — a table, a cleanup policy, a
decision about transactional boundaries between recording and acting — and doing it properly is the
whole point of a phase that is named after it.

**Kafka's exactly-once semantics.** Genuinely available with transactional producers and
`read_committed` consumers, and it only covers Kafka-to-Kafka. The moment a handler writes to
PostgreSQL, the guarantee stops at the boundary and something like the above is needed anyway.

## Consequences

**Good.** No extra infrastructure, and the rule is expressed exactly once. Duplicates are handled by
the same table that already defines what is legal, so there is no second definition of correctness to
keep in sync.

**Bad, and the reason this is explicitly interim.** It infers "already processed" from "not currently
legal", and those are not the same statement. A message that is genuinely impossible — a real bug —
is silently ignored exactly like a harmless duplicate. That is why `advance` logs both states at
`info` when it declines: that log line is the only trace distinguishing the two.

It also cannot detect a duplicate whose transition happens to be legal again later. Nothing in the
current flow revisits a state, so this is theoretical today and would stop being theoretical the
moment a retry or reopen path is added.

Phase 8 replaces the inference with a recorded fact.
