# 0054 — A closed account cannot queue

**Status:** Accepted · **Date:** 2026-10-25 · **Phase:** post-roadmap · **Amends [ADR 0053](0053-closing-an-account.md)**

## Context

[ADR 0053](0053-closing-an-account.md) took a closed account out of every waitlist when inventory heard
`AccountClosed`, and accepted one gap. A token issued before closing stays valid for up to twelve hours,
and it could still join a waitlist. The place it made would be told when stock came back and would hold
a unit for ten minutes with nobody to buy it, while every real shopper behind it waited. Nothing would
ever cancel that place, because the closure had already been processed.

## Decision

### Inventory remembers closed accounts, as order does

Inventory now keeps `closed_accounts`, the same table and the same `learned_at` column as the order
service, written when it hears `AccountClosed`. Joining a waitlist from a closed id is refused with 401,
the order service's answer to the same token at checkout.

### The join and the closure take the same lock

Checking `closed_accounts` before inserting is not enough on its own. Under read committed, a join can
read "not closed" while the closure's transaction is still open. The closure cancels that customer's
places and commits, and only then does the join insert a new one. That is exactly the gap this record
exists to close, reached by a narrower path.

So both take a transaction-scoped advisory lock on the customer (`pg_advisory_xact_lock`): the closure
before it records the id and cancels the places, and the join before it re-checks and inserts. Whichever
comes second waits for the first to commit. A join that waited behind a closure then sees it and is
refused, and a closure that waited behind a join then cancels the place the join made. The lock is per
customer, so nobody else's join ever waits on it. The early check, before the lock, stays as the cheap
answer for the common case of a token used well after closing.

## Verified

- `WaitlistIT`:
  - after `AccountClosed`, joining is a 401 and leaves no entry, while another shopper still joins;
  - deterministically: a closure's transaction is held open after it has cancelled, and a concurrent
    join is shown still waiting a second later. Once the closure commits, the join is refused with 401,
    and no `WAITING` place is left.
- Mutations: 5 of 6 caught. Caught: the join taking no lock; the closure taking no lock; no re-check
  under the lock; the closure not recorded; neither check. The survivor is the early check alone
  removed, which the re-check under the lock still covers, so it survives by design.
- CI's closure step now also checks that, once the place is `CANCELLED`, the same token cannot join again.

## Consequences

**Good.** No path is left by which a closed account makes stock wait. The leftover token's only remaining
reach is reading its own pseudonymous records.

**Accepted.**

- Every join takes a lock and makes one extra lookup. Both are per customer and cheap, and a join is not
  on the flash sale's hot path, which is the checkout.
- The table starts empty. Inventory's consumer group read the closures before this deploy without
  recording them, so an account closed in the twelve hours before the deploy can still join until its
  token expires. Closures from further back have no live token left. On the compose stack, the first
  closure after the deploy was recorded 1.6 s after it happened, and its token's rejoin got 401.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- `compose.yaml` does not pass `TRACE_SAMPLING` to the services ([ADR 0049](0049-loose-ends.md)).
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
