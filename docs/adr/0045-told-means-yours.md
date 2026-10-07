# 0045 — Told means yours

**Status:** Accepted · **Date:** 2026-10-07 · **Phase:** post-roadmap · **Amends [ADR 0044](0044-a-queue-for-what-is-gone.md)**

## Context

[ADR 0044](0044-a-queue-for-what-is-gone.md) gave sold-out SKUs a queue and told the oldest waiters when
units came back, one per unit. It named its own gap: **no unit was held for the shopper told**, so a buyer
who never queued could still take it first.

The gap was sharpest exactly where the queue mattered most. During a sale, a buyer's reservation first
reclaims expired holds on the SKU; that freed units, told the oldest waiter, and then the same buyer's
reservation took the freed unit in the next statement. The patient shopper was told about a unit that was
gone before the notice could be read. A queue whose notices mostly arrive too late teaches people to stop
using it.

ADR 0044 deferred the fix because it looked like a cross-service change: a unit held for a shopper is a
reservation the order service did not create, and the order service would have to adopt it.

## Decision

**Hold one unit for each shopper told, and let their own checkout adopt it — inside inventory, without
the order service knowing.**

### The hold

When the waitlist tells a shopper, it also reserves one unit for them: an ordinary reservation keyed
`waitlist:<entry id>`, in the customer's name, for `flashcart.inventory.waitlist.hold-ttl` (ten minutes by
default). It is made in the transaction that freed the units — the same one as the notice — and that
transaction already holds the SKU's stock row, so the units it is holding cannot have been taken in
between. `/waitlist/mine` shows it as `heldUntil`, and the `BackInStock` event carries it.

If a hold ever cannot be made, the shopper is still told, without one. The stock change that freed the
units is not rolled back: refusing a warehouse delivery over a courtesy hold would be the wrong way round.

### Adoption

Every reservation reaches inventory as a list of lines with a customer id, whether it came from the order
saga over Kafka or over HTTP. Before taking fresh stock for a line, inventory now looks for that
customer's live waitlist holds on that SKU and **adopts** them:

- the hold becomes `ADOPTED`, a new terminal state — it did not expire, was not released and was not
  sold; it became part of an order
- the unit is not released and reserved again. It is already reserved, and stays reserved, now for the
  order. The ledger gets one `ADOPTED` entry with **zero deltas**, so it still replays to the balance
- the rest of the line, if the order wants more than is held, comes from stock as before

Writing adoption as a release followed by a reserve would have been the obvious implementation and a
wrong one: the release would pass through the ledger hook as units coming back, and tell the next waiter
about a unit that never left the first one's hands. A test asserts the next waiter stays waiting, and a
mutation that writes it the obvious way is caught by it.

The sale's allocation and the customer's per-sale cap are charged exactly as before. Adoption changes
where the unit comes from, not whether this order may have it.

The order service is unchanged. It sends the same `ReserveInventory` it always did; the customer id in
it is what finds the hold.

### When nobody comes

An unused hold expires through the ordinary sweeper or lazy reclaim. Its unit comes back through the
ledger, so the next waiter is told and held for in turn: **the unit walks down the queue on its own**,
one hold window per shopper, until somebody buys it.

Expiring a waitlist hold publishes no `ReservationExpired`. That event's key is read by the order
service as an order id, and `waitlist:…` would fail to parse there, be retried and dead-letter. A hold
belongs to no order; its units coming back already reach the next waiter through the ledger.

### The receive response

`POST /stock/{sku}/receive` returns the stock position. A hold made inside it changes `reserved` through
a conditional `UPDATE` the returned entity never saw, so the response would have shown the held unit as
free. The entity is now re-read before it is returned, and a test asserts the receive's own answer shows
the unit held.

## What it costs

**A stranger is refused a unit that is physically on the shelf**, for up to ten minutes, because it is
held for somebody who may never come back. That is the point of holding it, and it is still a cost: in
the worst case a unit spends one hold window per waiter unsold. The window is configuration because the
right value depends on how fast shoppers react to a notice, which this platform cannot see yet — there
is still no notifier sending one.

**A hold is per unit.** A shopper told twice for the same SKU holds two units, and their checkout adopts
both.

## Verified

- **21 integration tests** in `WaitlistIT`, eight of them new: a told shopper holds the unit and a
  stranger is refused it; their checkout adopts it and the ledger still replays to zero after the sale;
  adoption tells nobody else; an order for more than is held adopts one and takes the rest; an unused
  hold lapses, passes to the next waiter and announces nothing to the order service; nobody else's
  checkout adopts it; a flash-sale checkout adopts it and is still charged to the allocation and the cap.
  The existing rejoin test's story — "the unit went to somebody else" — had become false, since that
  somebody is now refused; it was rewritten around a lapsed hold.
- **Six mutations**, each caught: no hold made, no adoption, adoption written as release + reserve,
  anybody's checkout adopting, expiry announcing waitlist holds, and the stale receive response.
- **The CI waitlist step** now goes end to end through the saga: a shopper queues, stock is received, a
  stranger who never queued checks out first and is **cancelled**, then the shopper checks out and their
  order goes through.

## Alternatives considered

**Change the order service to carry a hold key.** The saga would pass the waitlist hold to
`ReserveInventory` explicitly. More visible, and unnecessary: the customer id already in the command
identifies the hold, and keeping the change inside the one service that owns stock keeps the saga as it
was.

**Hold for the sale, not the SKU.** A waitlist hold has no flash sale. When the adopting order is
against one, the allocation and cap are charged at adoption — which is when the sale actually sells the
unit.

**Re-key the hold to the order's reservation.** Works for a one-line, one-unit order and for nothing
else. Adoption composes with any line.

## Consequences

**Good.** Being told now means being able to buy. Expired holds — most of a sale's churn — pass down the
queue instead of to whoever happened to reserve next.

**A reservation has a sixth state, and the ledger an eighth movement type.**

**Still open.**

- A notifier that consumes `BackInStock`; today a shopper finds out by asking `/waitlist/mine`, which
  makes the hold window's length a guess.
- Production accounts still get `OPERATOR`; the load harness still signs in as the operator.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
