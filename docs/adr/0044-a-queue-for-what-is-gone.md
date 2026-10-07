# 0044 — A queue for what is gone

**Status:** Accepted · **Date:** 2026-10-06 · **Phase:** post-roadmap · **Amended by [ADR 0045](0045-told-means-yours.md)**

## Context

A flash sale is designed to sell out, and until now a sold-out SKU was the end of the conversation. The
shopper's only option was to keep refreshing.

Units come back, though, and more often than "back in stock" suggests. Five things put units in front
of buyers again:

| how | how often |
|---|---|
| stock received | a warehouse event |
| stock adjusted up | a recount |
| a hold released | a declined card, an abandoned basket |
| **a hold expired** | **constantly, during a sale** |
| a sale returned | a paid order cancelled before dispatch ([ADR 0040](0040-a-cancelled-sale-gives-the-units-back.md)) |

Expired holds dominate: a sale sells out on *held* units, and a share of those holds lapse. "Sold out"
during a flash sale is usually "sold out for the next few minutes".

The platform had no notification channel of any kind — no email, no inbox — so this record also has to
say what "telling" someone means.

## Decision

**A queue per SKU, in inventory. When `n` units become available, the `n` oldest waiters are told, each
at most once.**

### Who is told

Strict queue order, and only as many as there are units. Not everyone: telling five hundred people about
three units rewards whoever checks out fastest, which is the opposite of what queueing for something
means. Waiters not told keep their place for the next units.

Being told is final. A shopper who was told and missed the unit joins again **at the back**: keeping a
place already used would let one shopper be told about every unit that ever comes back.

### Triggered from the ledger, not from the five paths

`MovementRecorder` was already documented as the one place every quantity change goes through, and each
ledger entry carries its own deltas. `on_hand_delta − reserved_delta` is exactly how many units that
movement put in front of buyers: positive for the five cases above, zero for a commit, negative for a
reservation. The waitlist hook sits there.

Hooking each path instead would have worked for five paths and silently failed for the sixth, whenever
somebody writes it. And the hook runs **in the transaction that freed the units**
(`Propagation.MANDATORY` enforces that there is one): the stock change, the claim of who is told, and the
outbox row announcing it commit together or not at all. A release that rolls back cannot leave somebody
told about units that never came back.

### Nobody told twice

The claim is one `UPDATE … WHERE id IN (SELECT … ORDER BY created_at, id LIMIT n FOR UPDATE SKIP
LOCKED) RETURNING *`, so choosing and marking cannot be separated.

Two transactions freeing units of the same SKU do not race each other at all, and it is worth being
precise about why, because the first draft of this record credited the wrong mechanism. Every one of the
five paths updates that SKU's `stock_items` row before it reaches the ledger, and that row stays locked
until commit. Concurrent arrivals are therefore already serialised, and the second one's claim sees the
first one's notices. A test fires five concurrent receives at a queue of ten and asserts as many distinct
shoppers were told as units arrived; it passes with or without the claim's own lock.

What the lock is for is a **shopper leaving while units arrive.** The leave holds the waitlist row, not
the stock row. Without `SKIP LOCKED` the claim blocks on that row, and when the leave commits the outer
`UPDATE` re-checks only `id IN (…)` — so it marks the now-`CANCELLED` entry as notified, telling a
shopper who just left and skipping the one behind them. With it, the claim passes over the row being left
and tells the next shopper. A test holds a leave open mid-transaction, delivers a unit, and asserts
exactly that.

### What "told" means here

A `BackInStock` event on `flashcart.inventory.events`, one per shopper, written through the outbox in that
same transaction, and the entry's status, readable at `GET /api/v1/inventory/waitlist/mine`. Nothing
consumes the event yet; it is what an email or push notifier would subscribe to. Building that notifier
is a separate service with its own failure modes, and the queue's correctness does not depend on it.

### The shopper's side

| | |
|---|---|
| `POST /api/v1/inventory/waitlist` `{"sku"}` | join; idempotent while waiting; `409 IN_STOCK` if units are available now |
| `GET /api/v1/inventory/waitlist/mine` | every queue joined, with `ahead` — how many are in front |
| `DELETE /api/v1/inventory/waitlist/{id}` | leave; harmless twice; `409 ALREADY_NOTIFIED` after being told |

These are the first paths in inventory a shopper may call, added to the filter's signed-in list. Each
acts as the token's subject and accepts no customer id, so there is nothing to name another shopper
with. Another shopper's entry is a `404`, the same as one that does not exist.

Joining refuses a SKU that has units available, because the joiner would be queued behind a notice that
already happened and would wait for the next one instead of buying this one.

The queue is for the **SKU**, not for a flash sale's price. A sale can exhaust its allocation while the
warehouse still holds general stock; that SKU is not sold out, and joining is refused.

## What being told does not mean

*Since closed by [ADR 0045](0045-told-means-yours.md): a shopper told now has a unit held in their name, which their own checkout adopts.*

**No unit is held for the shopper told.** Someone who never joined can still buy it first.

The sharpest form of this is the lazy reclaim. A buyer reserving a SKU first reclaims expired holds on
it, which frees units and tells the oldest waiters — and then that buyer's own reservation takes the
freed unit in the next statement. During a busy sale, a waiter can be told about a unit that was gone
before the notice was read.

Holding a unit for the shopper told would fix this, and it means a reservation the order service did not
create and would have to adopt: a cross-service change worth making deliberately rather than inside this
one. Recorded as open.

## Verified

- **14 integration tests** in `WaitlistIT`, covering every way units come back except the warehouse
  adjustment (which reaches the same hook as a receive), that a commit tells nobody, queue order,
  leaving, rejoining at the back, ownership, five concurrent arrivals and a leave racing a notice.
- **Nine mutations.** Eight are caught by the test meant to catch them:

  | mutation | caught by |
  |---|---|
  | the hook removed | eight tests, every path |
  | the reserved delta ignored | release and expiry tell nobody |
  | a reservation also notifies | a waiter is told about a unit that just left |
  | no `LIMIT` on the claim | the third waiter told about two units |
  | newest first | the wrong two told |
  | no `SKIP LOCKED` | the arrival blocks behind the leaving shopper |
  | no in-stock check | joining a SKU with units succeeds |
  | no ownership check on leave | somebody else's place given up |

  **The ninth survives, and should.** Removing the "already waiting?" check before inserting changes
  nothing a caller can see: the partial unique index refuses the duplicate and the fallback returns the
  existing place. The index is the guarantee; the check only spares a refused insert and the database
  error it logs. A mutation that cannot be caught because it is equivalent is a fact about the code, not
  a gap in the tests.
- The no-`SKIP LOCKED` mutation **survived the first round**, against the concurrent-arrivals test that
  could not see it. That is how the misattributed reasoning above was found.
- A CI step joins a queue as a shopper through the gateway, restocks as `WAREHOUSE`, and asserts the
  shopper was told — read immediately, without polling, because the notice is written in the restock's
  own transaction.
- `scripts/endpoint-coverage.py` reported the three new endpoints as exercised by nothing until the tests
  existed.

## Alternatives considered

**Tell everyone.** Simplest, and it turns a queue into a race the patient lose.

**A scheduled dispatcher** comparing availability with waiters every few seconds. It cannot tell new
units from units already announced without keeping a second count of its own, and it tells people late.

**A separate notification service now.** The right home for email and push. The queue — who is told, in
what order, exactly once — is inventory's to decide, and it is complete without a delivery channel.

## Consequences

**Good.** A sold-out SKU now has a next step. Units that come back reach the people who waited longest,
including the expired holds that are most of a sale's churn.

**A query on the hot path.** Every release and expiry now asks the waitlist for its SKU. It is a partial
index over `WAITING` rows, normally empty, and runs inside a transaction that was already open.

**Still open.**

- A held unit for the shopper told, so being told means being able to buy.
- A notifier that consumes `BackInStock`; today the notice is only readable in the API.
- Production accounts still get `OPERATOR`; the load harness still signs in as the operator.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
