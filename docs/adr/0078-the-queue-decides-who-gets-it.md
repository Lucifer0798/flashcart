# 0078 — The queue decides who gets it

**Status:** Accepted · **Date:** 2026-11-23 · **Phase:** post-roadmap · **Load-tests the waitlist of [ADR 0044](0044-a-queue-for-what-is-gone.md) and [ADR 0045](0045-told-means-yours.md)**

## Context

ADRs 0071 to 0077 load-tested sign-in and checkout. The third burst a flash sale produces had never been tested: a
sold-out item comes back, every shopper waiting for it is in the queue, and the queue has to decide who gets it. When
`n` units land, the `n` oldest waiters are told, each has a unit held in their name for ten minutes, and each is
emailed. Their checkout takes the held unit over, a stranger's cannot, and an unused hold lapses to the next in line.

`load/waitlist.sh [waiters] [units] [no-show-percent] [strangers]` exercises all of that at once:

- **Waiters:** they all queue for a sold-out SKU at the same moment. Then the warehouse receives the units. Each
  waiter watches their place, and those told check out at once, except a share who never come back.
- **Strangers:** shoppers who never queued try to buy the moment the stock lands.

The run fails unless all of these hold:

- nobody told out of turn: no waiter still waiting joined before anybody who was told;
- nothing oversold, and every unit sold while the queue still had takers;
- no stranger served while the queue still had takers;
- every told shopper who came back got the unit held for them;
- every lapsed hold belongs to a no-show, and every adopted hold to a told shopper who was served;
- exactly one back-in-stock email per shopper told, none dropped except as stale;
- nothing left held, nothing dead-lettered, no outbox row unsent, and nobody turned away.

So that unused holds lapse within one run, the harness restarts inventory with a short hold. `WAITLIST_HOLD_TTL`
defaults to two minutes here, against ten in production. It reads the setting back from inventory's `_info`, which now
reports `waitlistHoldTtl`, and refuses to run on anything else. It puts the ten minutes back when it finishes.

## What it found

**The queue itself was right from the first run.** Nobody was told out of turn, nothing was oversold, and no stranger
was served while the queue had takers. A no-show's hold lapsed and went to the next in line every time.

**But the rush at the restock stranded orders.** In one 2,000-waiter run, the strangers ordering at the moment of the
restock emptied the order service's pool of twenty connections, with up to 73 requests waiting. 95 were shed as 503.
**Eleven orders were committed with no reservation command behind them**, and sat in `CREATED` with no outbox row and
no reservation in inventory, untouched after ten minutes. Nothing would ever move them.

`OrderService.place` committed the order, and then queued its command in a second step. That ordering dates from
before the outbox (Phase 8). Publishing straight to Kafka inside the transaction could hold stock for an order that
then failed to persist, and an order without its command was "recoverable by retrying", but only if the client
retried with the same key. The outbox made the ordering unnecessary and left its cost. When the second step could not
get a connection, the order was committed and its command never written. `OutboxEventPublisher`'s own documentation
states the rule this broke: a failure to write the outbox row should fail the caller's transaction.

**And five checkouts failed with 500.** Catalog, under the same rush, sent a 200 whose JSON broke off. The order
service's catalog client caught catalog being unreachable, but not catalog being unreadable, so the
`RestClientException` became an unhandled 500.

## Decision

### The order and its command are one commit

`saga.requestReservation` is now called inside the transaction that creates the order. The outbox row and the order
commit together or not at all. A retry with the same idempotency key places the order properly, command included. With
the outbox turned off, as in some test suites, the direct publisher sends before the commit. A hold for an order that
then fails to persist expires with its reservation, which is the self-healing case that ordering was meant to avoid.

### An answer catalog could not finish is catalog unavailable

`RestCatalogClient` turns any `RestClientException` into `CatalogUnavailableException`, a 503 the shopper may retry,
the same as catalog being unreachable. A 404 is still an unknown SKU.

## Verified

- **`OutboxIT`:** with the outbox table renamed so the command cannot be written, placing an order fails and leaves
  **no order**. With the table back, a retry with the same key places it, with one command. Moving the command back
  after the commit fails this test.
- **`RestCatalogClientTest`:** against a local server, a 200 with broken JSON is `CatalogUnavailableException`, a 503
  likewise, and a 404 is not found. Letting the unreadable answer through again fails it.
- **Live, after the fixes:**

| | 2,000 waiters, 500 units | 3,000 waiters, 1,000 units |
|---|---|---|
| told out of turn | 0 | 0 |
| told, came back, and served | 500 of 500 | 1,000 of 1,000 |
| holds lapsed and passed on | 61 | 104 |
| strangers served | 0 of 500 | 0 of 1,000 |
| shipped, oversold | 500, no | 1,000, no |
| back-in-stock emails | 561, all sent | 1,104, all sent |
| stranded orders, 500s, refusals | none | none |

The pool did not run dry in these runs, so the stranded orders' fix was not exercised live. It rests on `OutboxIT` and
its mutation.

## What this leaves open

- **A hold's clock starts before its shopper can know.** Restocking 1,000 units claims and holds them in one
  transaction, and at 3,000 waiters nobody was told until at least 27 seconds after the restock. Each hold's clock
  starts at the claim, so those seconds come off every hold. Against ten minutes that is small. With the one-minute
  hold of an earlier run, 94 shoppers who came back lost their unit to it.
- **The back-in-stock email has a rate.** With a one-minute hold, only 383 of 1,104 were sent before they went stale,
  and stale ones are rightly not sent. With two minutes, all 1,104 were. At ten minutes that suggests room for a few
  thousand, but it was not measured there.
- **Eleven stranded orders remain on the local stack from the run that found the bug.** They are test data, and the fix
  stops new ones. No reconciler re-sends the command for an order already stranded that way. The client's retry with
  the same key was always the only way back, and it still works.
