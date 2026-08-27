# 0004 — Flash-sale liveness is derived, never stored

**Status:** Accepted · **Date:** 2026-08-27 · **Phase:** 2

## Context

A flash sale has a start and an end. The storefront needs to know which sales are live right now, and
pricing depends on the answer being correct at the instant it is asked.

The obvious modelling is a `status` column holding `SCHEDULED → ACTIVE → ENDED`, advanced by a
scheduled job.

## Decision

Store only the admin's **intent** — `DRAFT`, `SCHEDULED`, `CANCELLED` — and **derive** liveness from
the window on every read (`FlashSale#phase(Clock)`):

```
CANCELLED               → CANCELLED
DRAFT                   → DRAFT
now <  startsAt         → UPCOMING
startsAt ≤ now < endsAt → ACTIVE
now ≥ endsAt            → ENDED
```

The window is half-open: `startsAt` inclusive, `endsAt` exclusive.

## Alternatives considered

**A stored status flipped by a scheduler.** Every second the scheduler is late is a second the
storefront sells at the wrong price — the top of a flash sale being exactly when it is most loaded
and most likely to be late. It also introduces a class of bug where a job failure leaves the
database claiming something untrue, needing a reconciliation job to fix a problem that only exists
because the flag exists.

**A database view or generated column.** Correct, but it moves the rule out of the domain model,
where the rest of the pricing logic lives, and makes it untestable without a database.

## Consequences

**Good.** Liveness cannot go stale — it is a pure function of the row and the clock. No scheduler, no
sweeper, no reconciliation. Cancelling a live sale takes effect on the very next read with nothing to
clean up. Because the function is pure and `Clock` is injected, the boundaries are testable to the
second without sleeping (`FlashSalePhaseTest`).

The half-open window means two back-to-back sales on one product can never both be live for the
instant they touch — which matters, because when a product is in two live sales the cheaper one wins,
and that tie-break should never fire by accident.

**Bad.** Every read does the comparison, and "find live sales" is a range scan rather than an
equality lookup on an indexed flag. Mitigated by a partial index
(`... where status = 'SCHEDULED'`), and the purity is exactly what makes the result safe to cache in
Phase 7.

Callers cannot query on liveness in raw SQL without repeating the predicate; the repository owns the
one canonical version (`findLive`).
