# 0009 — No database transaction spans a call to another service

**Status:** Accepted · **Date:** 2026-08-31 · **Phase:** 4

## Context

Placing an order does three things: price the basket from catalog, persist the order, hold the stock
in inventory. The obvious implementation wraps all of it in one `@Transactional` method, so that a
failure anywhere rolls the whole thing back.

## Decision

Keep transactions small and local, put the network calls *between* them, and write the compensation
out explicitly. `OrderService.place` is deliberately **not** `@Transactional`; it opens short
transactions through a `TransactionTemplate` around the database work only.

## Alternatives considered

**One transaction around the whole checkout.** Two problems, either of which is disqualifying.

It holds a database connection open across two HTTP calls. A downstream that slows to three seconds
turns every in-flight checkout into a connection held for three seconds, and the pool is exhausted
long before the timeouts fire — one struggling dependency takes this service down with it.

And it does not actually work. A rollback undoes the local rows; it does not un-reserve the stock
inventory is now holding. The transaction gives the *appearance* of atomicity across a boundary it
cannot reach, which is worse than not having it, because it stops anyone writing the compensation
that is actually needed.

**Distributed transactions (XA).** Genuinely atomic, and it requires every participant to hold locks
until the coordinator decides — which is precisely what a flash sale cannot afford, and why the
reservation model exists at all.

## Consequences

**Good.** A slow downstream costs a request thread, not a database connection. Every failure path is
written down rather than delegated to a rollback that would not have covered it. The three outcomes
of a reserve — accepted, refused, no answer — are handled on their own terms, which a single
try/catch around a transaction would have flattened into one.

**Bad.** There is a window between persisting the order and holding the stock in which the process
could die, leaving an order in `CREATED` with no reservation. That is exactly why `CREATED` is a
persisted state and why `place` resumes rather than short-circuits when it finds one: the order is
recoverable by retrying with the same idempotency key. Phase 8's outbox narrows this window further.

Compensation is now the author's responsibility, and a missed path is a real bug rather than a
missing annotation. That is the trade: correctness that has to be written, instead of correctness
that was never actually there.
