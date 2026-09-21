# 0030 — Cancelling a paid order

**Status:** Accepted · **Date:** 2026-09-21 · **Phase:** post-roadmap

## Context

The state machine allowed these two edges:

```java
ALLOWED.put(OrderStatus.PAID,       EnumSet.of(FULFILLING, CANCELLED));
ALLOWED.put(OrderStatus.FULFILLING, EnumSet.of(SHIPPED,    CANCELLED));
```

`POST /orders/{n}/cancel` let a customer take either one. Taking it did this:

- `holdsInventory()` is `RESERVED || PAYMENT_PENDING`, so **no release was published** — correct as
  far as it went, because by `PAID` the units have been committed and are genuinely sold
- the order moved to `CANCELLED`
- the payment stayed `COMPLETED`

There was no `RefundPayment` command and no `PaymentRefunded` event; payment consumed exactly one
message. **So the customer lost the goods and the money.** The enum said otherwise:

```java
/** Terminal. Stock is back on the shelf and any capture has been refunded. */
CANCELLED(true);
```

Neither clause was true on those two edges. The word *refund* appeared seven times in the codebase,
every one of them a comment explaining a hazard being avoided — `PaymentTimedOut` exists precisely
so the saga will not release stock on a charge that might still land "and then owe a refund". The
platform had reasoned carefully about refunds it might one day owe while having no way to pay one.

Nothing tested either edge. Removing both broke no test.

## The narrow window, and the window people actually want

The two edges were also nearly unreachable. `onPaymentCompleted` transitions `PAID` and then
`FULFILLING` in the same handler, and `ShipmentCreated` is published the moment shipping books the
consignment, so an order passes through both states in about a second. The capability was
simultaneously wrong and almost impossible to invoke.

Meanwhile the state a customer actually wants to cancel from — paid for, boxed, **not yet
dispatched** — is `SHIPPED`, and `SHIPPED` allowed only `DELIVERED`. The name is misleading:
`SHIPPED` means a shipment record exists, not that anything has moved.

## Decision

**Cancelling a paid order is a request, made from `SHIPPED`, and shipping answers it.**

```
SHIPPED ─▶ CANCELLATION_REQUESTED ─▶ CANCELLED  (consignment stopped, capture refunded)
                                  ─▶ SHIPPED    (refused; the parcel had already left)
```

`PAID → CANCELLED` and `FULFILLING → CANCELLED` are **removed**. Cancelling during that second now
returns 409 with a message saying to try again shortly, which is true, rather than silently doing
something that costs the customer twice.

### Only shipping can answer, so only shipping decides

This is the single reason the flow has a waiting state rather than being a local decision. The order
service cannot know whether the parcel has left; that fact lives in one table in one service. Asking
is the only correct shape, and it makes `CancelShipment` the odd one out on a command topic — every
other command instructs a service to do something it is able to do, and this one asks a question.

**Both answers are events.** A refusal that only logged would leave the order in
`CANCELLATION_REQUESTED` forever, waiting for a reply that was never coming. The refusal carries the
shipment's status so the order's history records *why* it failed, which is what answers "I cancelled
this and it arrived anyway".

### Refund on the answer, never on the request

The money moves when shipping confirms the goods did not. Refunding on the request would pay out for
parcels that turn out to have already been dispatched — the same mistake as releasing stock on a
payment timeout, in the opposite direction.

### The refund has its own idempotency key

`refund:<order id>`, not the order id. The order id is already the charge's key: one key for both
would collide in payment's unique index on `idempotency_key`, and would invite a provider to read
the refund as a repeat of the capture. A refund paid twice is cheaper than a charge taken twice and
no less wrong.

### `REFUND_FAILED` is a state of its own

A provider that will not reverse a capture leaves the platform holding money for an order it has
already told the customer is cancelled. Folding that into `FAILED` would hide it among charges that
never happened — the one state where every other part of the system believes the customer has been
made whole and only this row disagrees. It stays refundable, because the money is still ours to
return.

Two counters, following [ADR 0018](0018-metrics-answer-questions-the-logs-cannot.md) and
[ADR 0029](0029-an-operator-read-was-a-silence.md): `flashcart_payment_refunds_total{outcome}`.
`completed` is a business fact and gets a panel; `refused` is wrong on its own terms and alerts.

### What is *not* given back: the stock

The committed units do not return to inventory. Putting them back raises questions this change is
not the place to answer — whether they rejoin the flash sale's allocation or general stock, and
whether the customer's per-sale cap under [ADR 0008](0008-atomic-counters-for-caps-and-allocations.md) is
refunded with them. Those counters are atomic and separate by design, and quietly incrementing them
from a cancellation would be deciding a fairness question by accident.

This is a real cost and it is named here rather than hidden: a cancelled paid order currently
consumes a unit that is neither sold nor available. In a flash sale, where the sale is usually over
by the time anyone cancels, that is tolerable. It would not be in general commerce.

## Alternatives considered

**Leave the edges and add a refund behind them.** The obvious reading of "add refunds", and wrong.
It would refund orders whose parcels had already been dispatched, because nothing in the order
service knows the difference. A refund that can pay out for goods in a van is worse than no refund:
it converts a visible gap into an invisible loss.

**Remove the edges and stop there.** Sound, and it closes the hole — but it leaves a paid order
uncancellable forever, which is not a commerce platform. The shipping round trip *is* the feature.

**Allow the request from `PAID` and `FULFILLING` too.** Rejected on a race. `CreateShipment` and
`CancelShipment` are consumed by different consumer groups, so a cancellation sent before the
creation was handled could reach a shipment that does not exist yet — and answering "nothing to
cancel" would then book a parcel for a cancelled order. Requiring `SHIPPED` removes the race
entirely, because the event that puts an order there is shipping's own confirmation that the row
exists.

**A new `REFUND_PENDING` order state.** Rejected for symmetry with what was already here: the order
moves to `CANCELLED` and the money follows, exactly as it moves to `CANCELLED` and the stock follows
on a declined payment. The order has never tracked money; the payment does, and a customer can read
it.

## Consequences

**Good.** A customer can cancel a paid order that has not shipped and get their money back, and one
whose parcel has left is told so with the reason on the record. `ShipmentStatus.CANCELLED` and the
`CHECK` constraint that has listed it since the first migration finally mean something.

**Two consumer groups were added, and that is load-bearing.** Two listeners sharing a group on one
topic are two members of that group: Kafka gives each a share of the partitions, and whichever owns
one filters away and acknowledges every message of the other's type. Not a warning, not lag — both
listeners silently missing most of their own messages.

**`PaymentRefunded` currently has no consumer.** It is an event, and the topic doc says publishers
do not care who subscribes; the order is already `CANCELLED` by the time it lands, so there is no
state left for it to move. The customer sees the refund on the payment itself.

**Still open.**

- **A refused refund has no retry path.** `REFUND_FAILED` is refundable by design, but nothing
  re-sends the command; the alert brings a human who has no button to press. That is the next thing
  to build on this surface.
- **A cancellation request has no sweeper.** If shipping's answer is lost, the order sits in
  `CANCELLATION_REQUESTED`. There is a partial index for finding them and nothing that looks.
- **`OrderStatus.DELIVERED` is unreachable.** Shipping's `deliver` sets its own status and publishes
  nothing, and the saga has no handler, so no order has ever been `DELIVERED` — the same kind of dead
  state `ShipmentStatus.CANCELLED` was until this change. Found while writing these tests, left
  alone deliberately rather than bundled.
- Cancelled paid orders do not return their units to stock, as above.
- Per-service ports remain published, the publisher still stores a hard-coded sampled flag, and the
  `operator_access_log` still has no reader across three databases.
