# 0033 — SHIPPED did not mean shipped

**Status:** Accepted · **Date:** 2026-09-23 · **Phase:** post-roadmap · **Completes work deferred by [ADR 0030](0030-cancelling-a-paid-order.md) and [ADR 0032](0032-an-order-that-can-finish.md)**

## Context

`OrderStatus.SHIPPED` meant *a consignment record exists*. It did not mean the parcel had left, and
[ADR 0030](0030-cancelling-a-paid-order.md) said so while relying on it:

> Meanwhile the state a customer actually wants to cancel from — paid for, boxed, **not yet
> dispatched** — is `SHIPPED` … The name is misleading: `SHIPPED` means a shipment record exists, not
> that anything has moved.

The order had no state for the parcel actually leaving, and `ShipmentService.dispatch` was the last
transition in shipping that happened in silence — it set its own status and told nobody, exactly as
`deliver` did before ADR 0032.

Two things followed from that, both named as open and neither fixed:

**Refusing a cancellation cost a round trip to learn something already known.** A customer cancelling
a dispatched order sent `CancelShipment`, the order moved to `CANCELLATION_REQUESTED`, shipping
answered `ShipmentCancellationRefused`, and the order went back to where it started. Three messages
to discover a fact shipping had recorded hours earlier.

**And then it could happen again.** The refusal returned the order to `SHIPPED`, so the customer
could ask again, and be refused again, indefinitely.

## Decision

**Give the order a `DISPATCHED` state, and let shipping announce dispatch.**

```
SHIPPED ─▶ DISPATCHED ─▶ DELIVERED
```

`DISPATCHED` has no cancellation edge, so `OrderService.cancel` refuses locally and immediately with
`ORDER_NOT_CANCELLABLE`. `SHIPPED` keeps its cancellation edge, which is the window that was always
meant.

### Shipping is still the authority

This does not take the decision away from shipping, which is what [ADR 0030](0030-cancelling-a-paid-order.md)
insisted on. The order is keeping *what it was told*, and using it only to refuse.

The distinction that makes this safe: **refusing early is always sound, granting early is not.** A
stale "already dispatched" is impossible, because the order only enters `DISPATCHED` by being told it
had. A stale "not yet dispatched" is entirely possible — and it is the case where the order still
asks, and shipping still settles it. The race where a parcel is dispatched between the customer
asking and shipping handling the command is unchanged, and still answered by shipping.

### A refusal resolves forward

`CANCELLATION_REQUESTED` now leads to `CANCELLED`, `DISPATCHED` or `DELIVERED` — never back to
`SHIPPED`. Shipping only ever refuses *because* the shipment is `DISPATCHED` or `DELIVERED`, so the
refusal is itself news about where the goods are, and the order records it.

**The edge back to `SHIPPED` had to go, not merely become unused.** The moment `DISPATCHED` exists,
nothing can take it: there is no shipment status that produces a refusal and leaves the order
rightly in `SHIPPED`. Leaving it would have created a fourth instance of the thing the last three
ADRs each found — [`ShipmentStatus.CANCELLED`](0030-cancelling-a-paid-order.md) in a `CHECK`
constraint nothing could satisfy, [`REFUND_FAILED`](0031-when-nobody-answers.md) with no exit,
[`OrderStatus.DELIVERED`](0032-an-order-that-can-finish.md) that no order ever reached — this time
authored deliberately, in the same change that removed the reason for it.

### `SHIPPED → DELIVERED` stays, and is not redundant

It looks like dead weight beside `SHIPPED → DISPATCHED → DELIVERED`. It is required.

Dispatch and delivery are consumed by **separate consumer groups**, which is not a choice: two
listeners sharing a group on one topic split the partitions between them, and whichever owns one
filters away and acknowledges every message of the other's type. Separate groups mean each reads
every partition in order — but nothing orders the two groups against each other. A delivery can be
applied before the dispatch that preceded it.

Without this edge, that delivery is declined and the arrival is lost; the order sits in `SHIPPED`
with a delivered parcel. With it, the delivery lands and the late dispatch is declined instead, which
is the harmless direction. The cost is that the saga's declined-transition counter ticks in a case
that is not an anomaly — rare enough to accept, and named here so nobody reads it as one.

## Alternatives considered

**Leave the boundary inside shipping and keep asking.** What ADR 0030 built, and correct at the time
— the order genuinely did not know. It stops being correct once the order can know, at which point
the round trip is three messages to learn a recorded fact, and the customer can repeat it forever.

**Move `FULFILLING → SHIPPED` to fire on dispatch instead of creation**, so `SHIPPED` means what it
says without a new state. Rejected: it destroys the cancellation window. The state a customer cancels
from is precisely "record exists, not yet gone", and this collapses it to nothing.

**Have the refusal resolve to `DELIVERED` always**, rather than reading the shipment status. Simpler,
and wrong in the common case: most refusals are for a parcel in transit, and an order claiming it had
arrived would be a statement shipping never made.

## Consequences

**Good.** A dispatched order is refused cancellation in one hop with a message that says why, the
customer cannot loop on it, and `SHIPPED` now means what the rest of the platform assumed it did.
Shipping has no silent transitions left.

**The saga declines a transition in one non-anomalous case**, as above.

**A third listener on the shipping topic**, each with its own group, each reading every partition.
That is the cost of the arrangement ADR 0030 established and is worth restating: it is not
duplication, it is the only shape in which two message types on one topic both get consumed.

**Still open.**

- Cancelled paid orders still do not return their units to stock ([ADR 0030](0030-cancelling-a-paid-order.md)),
  which remains a flash-sale allocation question rather than a coding one.
- An abandoned refund still has no button ([ADR 0031](0031-when-nobody-answers.md)).
- `operator_access_log` still has no reader, across three databases.
- Per-service ports remain published; the publisher still stores a hard-coded sampled flag.

*The audit reader was since built by [ADR 0034](0034-who-read-my-data.md): each service exposes its own, since each has its own table.*
