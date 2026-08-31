# 0007 — Reservations expire on two paths, lazily and on a schedule

**Status:** Accepted · **Date:** 2026-08-28 · **Phase:** 3

## Context

Between "buy now" and "payment confirmed" sits a person typing a card number and a payment provider
taking seconds to answer. Something has to stop those units being sold to someone else in the
meantime, and it cannot be a database lock — holding one across a human is how a flash sale takes the
database down.

So stock is *held*: a durable, bounded claim that costs nothing while it waits. Which raises the
question this ADR is actually about — what happens when the holder never comes back.

## Decision

A reservation carries an `expires_at`, and expired holds are reclaimed by **two** mechanisms:

**Lazily, on the reserve path.** Before trying to hold a SKU, reclaim any expired holds touching it.
This is the one that matters for correctness of experience: a buyer must never be told "sold out"
because a background job had not yet got round to units that expired thirty seconds ago. It is
bounded (`lazy-reclaim-limit`) so one unlucky request never inherits an unbounded backlog, and it
uses `SELECT ... FOR UPDATE SKIP LOCKED` so concurrent buyers do not queue behind each other trying
to do the same reclaim.

**On a schedule**, for everything the lazy path never touches — a SKU nobody is asking about any
more, a sale that has ended. Without it, a cold SKU's expired holds sit there forever: a slow stock
leak rather than a visible failure. It is also where Phase 5 will publish `ReservationExpired`, which
is what lets the order service move its own state machine.

The reclaim runs in its **own transaction**, so units that genuinely expired stay freed even when the
reservation attempt that noticed them goes on to fail.

Committing an expired hold is refused with a 409. Those units are back in the pool and may already
belong to someone else, so the caller must reconcile rather than confirm an order it cannot fill.

## Alternatives considered

**Sweeper only.** Simpler, and wrong in the worst moment. Between a hold expiring and the next sweep,
its units are invisible — and the sweeper is most likely to be behind exactly when the sale is
busiest. Buyers get told "sold out" while stock sits reclaimable.

**Lazy only.** Less machinery, and it leaks. Stock returns only if someone happens to reserve that
SKU again; a product that goes quiet keeps its expired holds forever, and nothing emits an event for
Phase 4's state machine to react to.

**A TTL in Redis.** The obvious tool, and deferred to Phase 7 by design: it would make Redis the
system of record for the number the platform cannot afford to lose, before there is a durable,
demonstrably correct Postgres implementation to compare it against. Phase 7 adds Redis in front of
this, not instead of it.

## Consequences

**Good.** A buyer never sees a false "sold out". Nothing leaks. Correctness does not depend on a
scheduled job running on time, only tidiness does. Expiry is testable without sleeping through a real
TTL, because `Clock` is injected and the sweeper batch can be driven directly.

**Bad.** Two paths to keep in agreement — the entity's `isExpired(Clock)` uses `>=` and the SQL uses
`expires_at <= now()`, and they must keep matching or the two disagree for exactly one second. There
is a test asserting precisely that boundary.

Every reserve pays for a reclaim query on each of its SKUs, even when there is nothing to reclaim.
Cheap (a partial index on `status = 'HELD'`, `LIMIT`, `SKIP LOCKED`) but not free, and worth
revisiting in Phase 7 when Redis can answer "is anything expired here" without touching Postgres.

The reclaim's `REQUIRES_NEW` transaction caused a real outage in testing: called from *inside* the
reservation transaction it needed a second pool connection while holding the first, and at sixty
concurrent buyers against a twenty-connection pool it deadlocked the pool outright. The fix — run the
reclaim before opening the reservation transaction — is load-bearing, and `ReservationService.reserve`
is deliberately not `@Transactional` because of it.
