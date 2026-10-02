# 0040 — A cancelled sale gives the units back

**Status:** Accepted · **Date:** 2026-10-02 · **Phase:** post-roadmap · **Amends [ADR 0030](0030-cancelling-a-paid-order.md)**

## Context

[ADR 0030](0030-cancelling-a-paid-order.md) made a paid order cancellable: shipping stops the
consignment, the saga moves the order to `CANCELLED`, payment refunds the capture. It then said, in a
section headed *What is not given back*:

> The committed units do not return to inventory. Putting them back raises questions this change is
> not the place to answer — whether they rejoin the flash sale's allocation or general stock, and
> whether the customer's per-sale cap … is refunded with them.

Every ADR from 0031 to 0039 carried that forward in its *still open* list. Nine in a row.

The cost was precise. After a commit, three counters say the unit is gone — `on_hand` is down, the
sale's `committed_units` is up, and the customer's `consumed_units` stays charged. A cancellation after
payment undid none of them, so
each one left a unit that was **neither sold nor available**: physically on the shelf, refunded to the
customer, and counted as gone by every number the platform reads. On a sale of five hundred units,
twenty cancellations meant the sale could only ever sell four hundred and eighty, and the twenty
shoppers who cancelled could not buy again.

ADR 0030 judged that tolerable because the sale is usually over by the time anyone cancels. That is
not what the state machine says: `SHIPPED` is the cancellable state, and an order reaches it seconds
after payment, inside the sale.

## Decision

**When shipping confirms the goods never left, return them: the exact inverse of the commit, plus the
cap.**

The saga sends `ReturnInventory` on `ShipmentCancelled`, beside `RefundPayment` and on the same answer.
Inventory moves the reservation from `COMMITTED` to a new `RETURNED` state and reverses every counter:

| counter | commit | return |
|---|---|---|
| `stock_items.on_hand` | − q | **+ q** |
| `sale_allocations.committed_units` | + q | **− q** |
| `customer_sale_limits.consumed_units` | unchanged (stays charged) | **− q** |
| ledger | `COMMITTED` (−q on hand) | `RETURNED` (+q on hand) |

### The first open question was not a choice

ADR 0030 asked whether returned units should rejoin the sale's allocation *or* general stock. Reading
the schema answers it: **an allocation is not a bin of stock.** `sale_allocations` is a cap applied on
top of the one `stock_items.on_hand` figure for the SKU ([ADR 0008](0008-atomic-counters-for-caps-and-allocations.md)).
There is no separate pool for the sale to hold.

So returning to on-hand alone already makes the units available to general stock. The only question is
whether the sale gets them back *as well*, and the answer is yes: leaving `committed_units` alone would
put the units on the same shelf while the sale stayed permanently short of what it advertised. Undoing
the commit does both, because both are what the commit did.

After a sale ends, the extra headroom in its allocation is inert: the units are bought at the ordinary
price, because catalog prices from the live sale and not from the allocation row.

### The second was a choice, and it goes the customer's way

`consumed_units` is documented as *units held plus units already bought*. A cancelled, refunded unit is
neither, so it stops counting. The alternative — keep it charged — would turn "one per customer" into
"one attempt per customer", and punish exactly the shopper who handed a unit back.

The obvious objection is churn: buy, cancel, buy again. It gains nothing. At no moment does the
customer hold more than the cap, each cycle costs a capture and a refund, and cancellation after
payment needs shipping's confirmation that the parcel has not left, which is not a fast loop.

### On shipping's answer, not on the request

Returning on the customer's request would put units on the shelf that turn out to be in a van — and
then sell them again. It is the same reason ADR 0030 refunds on the answer, and the same mistake as
releasing stock on a payment timeout.

The return and the refund **do not wait for each other.** The goods and the money are independent
facts; a refund the provider refuses and [ADR 0031](0031-when-nobody-answers.md) retries is no reason to
keep goods off a shelf they are demonstrably on.

### `RETURNED`, not `RELEASED`

A release gives back a hold that never became a sale; a return gives back a sale. They differ in
which counters move — a release never touched on-hand — and support needs to tell them apart.
`committed_at` is kept on a returned reservation, because the sale did happen.

## A return can overtake its commit

Commands for one order travel on one partition, but each command type has its own consumer group, and
groups do not wait for each other. A commit group that is lagging can let `ReturnInventory` arrive
while the reservation is still `HELD`.

A held reservation is **refused**, not settled. The handler throws, the claim in `processed_events`
rolls back with it, and the listener retries until the commit lands. Settling it instead — releasing
the hold — would make that pending commit fail and dead-letter. A test publishes the return *first*,
then the commit, and asserts the stock ends where it should.

If the commit group is down for longer than the retry budget, the return dead-letters and the units
stay committed. That is today's behaviour before this change, visible rather than silent.

### Not an exception for "no such reservation"

`returnToStock` returns `Optional.empty()` for an unknown key rather than throwing
`ResourceNotFoundException`. It runs inside the listener's transaction, and an exception escaping a
joined `@Transactional` method marks the whole transaction rollback-only even when the caller catches
it. Writing the broker test for this exposed the existing release path doing exactly that: a
`ReleaseInventory` for a key that never existed is retried and dead-lettered rather than reported
released, as its comment intends. That is a separate defect and is not fixed here.

## Verified

Each part of the return was broken in turn and the suite went red:

| mutation | caught by |
|---|---|
| allocation not returned | `committed_units` still 1 |
| cap not restored | the buyer's second reservation refused with `CUSTOMER_LIMIT_EXCEEDED` |
| a `HELD` reservation silently accepted | no exception; over the broker, on-hand settles at 2 where 5 exist |
| a second return processed again | the allocation guard: *fewer than 2 committed units* |
| a released hold "returned" | status `RETURNED` where it must stay `RELEASED` |

Those are each service's half. Nothing checked that the halves meet: CI had no paid-cancel flow at
all, so the path from shipping's answer through the saga to inventory had never run end to end. It
now does — CI places an order, waits for `SHIPPED`, cancels it, waits for `CANCELLED`, and asserts
on-hand is back where it started.

## Alternatives considered

**Return to general stock only.** On this schema it is not a distinct option; see above. Its only
effect would be to leave the sale permanently short.

**A `ReturnInventory` that names its quantities.** Rejected for the same reason `RefundPayment` does not
let the command choose the amount: inventory already knows exactly what the reservation committed, and
a command that could say otherwise would be a way to invent stock.

**An HTTP endpoint for returns.** No caller needs one. Correcting stock by hand is what `ADJUSTED` is
for, and it always carries a reason; a second manual route that bypasses that would be worse.

**Wait for the refund before returning.** Couples two facts that are independent, and makes a
provider outage keep goods off the shelf.

## Consequences

**Good.** A cancellation after payment no longer shrinks the sale. The unit is available to the next
buyer as soon as inventory processes the return, at the sale's price while the sale runs, and the
customer who handed it back may buy again.

**A reservation has a fifth state**, and every reader of `status` must treat `RETURNED` as terminal.
`isTerminal()` already does, being defined as "not `HELD`".

**Still open.**

- `ReleaseInventory` for a reservation that does not exist dead-letters instead of being reported
  released (found above).
- Production accounts still get `OPERATOR`; the load harness still signs in as the operator.
- Whether a customer may read their own access log, and the operator-scoped view
  ([ADR 0034](0034-who-read-my-data.md)).
- An abandoned refund has no button ([ADR 0031](0031-when-nobody-answers.md)); the publisher still stores
  a hard-coded sampled flag; catalog has no `_info` endpoint.
