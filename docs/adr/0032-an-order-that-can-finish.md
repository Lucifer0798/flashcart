# 0032 — An order that can finish

**Status:** Accepted · **Date:** 2026-09-22 · **Phase:** post-roadmap

## Context

`OrderStatus.DELIVERED` had never been reached by any order, ever.

`ShipmentService.deliver` set the shipment's own status and published nothing. `OrderSaga` had nine
handlers and none of them was for delivery. There was no `ShipmentDelivered` event to handle. So
every order that shipped stayed `SHIPPED` for the rest of time, and the platform's only successful
terminal state was decoration.

Three places described it as the normal end of the happy path:

```
README.md              CREATED ──▶ ... ──▶ SHIPPED ──▶ DELIVERED
docs/architecture.md   SHIPPED --> DELIVERED
                       DELIVERED --> [*]
OrderStateMachine      ALLOWED.put(SHIPPED, EnumSet.of(DELIVERED, ...))
```

and `OrderStateMachineTest.noStatusIsAccidentallyStranded` asserted that `DELIVERED` is terminal —
a test that passed for a state nothing could enter.

This is the third of these found in four changes. `ShipmentStatus.CANCELLED` was listed in the first
shipping migration's `CHECK` constraint and unreachable until [ADR 0030](0030-cancelling-a-paid-order.md).
`REFUND_FAILED` was reachable but had no exit until [ADR 0031](0031-when-nobody-answers.md). The
pattern is consistent enough to name: **a state that the schema, the enum, the docs and a test all
describe is not thereby a state the system can be in.** The declarations agree with each other, which
is exactly why nobody notices they agree about something that never happens.

## Decision

**Shipping publishes `ShipmentDelivered`, and the saga moves `SHIPPED → DELIVERED`.**

The event carries `deliveredAt` from the warehouse rather than leaving the consumer to stamp its own
clock. The parcel arrived when shipping says it did, and an order whose history reads a second or two
later than the shipment it describes is the sort of discrepancy that costs somebody an afternoon.

**`deliver` re-publishes when called on an already-delivered parcel**, matching `create` and
`cancel`. A repeat is far more likely to mean the first event was lost than that anything is wrong —
an operator scanning a parcel twice is ordinary, and an order content to sit in `SHIPPED` forever is
what this ADR exists to stop.

**The saga's handler is idempotent by the usual route**: `DELIVERED → DELIVERED` is not an edge, so
`advance` declines a redelivery and does nothing. Since shipping now re-publishes, that path is
walked in normal operation rather than only in theory.

### `OrderDelivered` goes on the order topic

Nothing inside this platform subscribes to `ORDER_EVENTS` — `OrderConfirmed` and `OrderCancelled`
are both published to it and both unconsumed. That topic is the seam for whatever lives outside the
repo, which is what [ADR 0017](0017-outbox-and-processed-events.md)'s split between commands and
events is for, and "your order arrived" is the most obvious notification anyone would ever build.

Publishing the two unhappy endings and not the happy one would leave the only good outcome missing
from the stream that exists to carry outcomes. This completes an existing pattern rather than
inventing a new one, which is the distinction that makes it worth doing and would not have justified
a third unconsumed mechanism of its own design.

### What this closes

`DELIVERED` is terminal with no edge out, so a delivered order is refused a cancellation outright
rather than turning into a request nobody can answer. [ADR 0030](0030-cancelling-a-paid-order.md) had
to **drop** that test — the boundary existed in the state machine and could not be exercised, because
no order could be delivered. It is now covered end to end.

Returning goods that have arrived is a different transaction, and remains deliberately unbuilt.

## Alternatives considered

**Publish `ShipmentDispatched` as well.** The other event shipping does not emit, and left alone. The
order has no state for "the parcel left" — `SHIPPED` already means "a consignment record exists" —
so the event would have nothing to move and no subscriber, which is the shape-without-purpose this
change explicitly avoided in choosing `OrderDelivered`. It becomes worth doing alongside the state
below, not before it.

**Give the order a `DISPATCHED` state, so `SHIPPED` means what it says.** Tempting, and a real
improvement: the cancellation boundary would live in the order's own machine rather than only inside
shipping, and a refused cancellation could return the order to `DISPATCHED` instead of back to
`SHIPPED`, where the customer can immediately ask again and be refused again. Deliberately not
bundled — it moves the meaning of an existing state, which deserves its own argument rather than
arriving inside a change about delivery.

**Let the saga stamp its own delivery time.** Simpler, and wrong for the reason above: it makes two
records of one event that disagree by however long the bus took.

## Consequences

**Good.** An order can finish. The happy path drawn in the README is now a path the platform can
actually walk, and the seven-transition history a delivered order carries is assertable end to end.

**`OrderDelivered` has no consumer**, like the two events beside it. That is the topic's stated
contract and not a defect, but it now applies to three events rather than two, and is worth naming
each time rather than letting it become invisible.

**Still open** *(at the time of writing — see below)*.

- **`SHIPPED` still means "a consignment exists", not that anything moved**, and there is still no
  order state for dispatch. Named in ADR 0030 and again above; the next thing to pull on this
  surface.
- **A refused cancellation returns the order to `SHIPPED`**, so a customer can ask again and be
  refused again indefinitely. Harmless and untidy; it resolves with a `DISPATCHED` state.
- Cancelled paid orders still do not return their units to stock (ADR 0030); an abandoned refund
  still has no button (ADR 0031); per-service ports remain published; the publisher still stores a
  hard-coded sampled flag; `operator_access_log` still has no reader across three databases.

*Both of the first two were since closed by [ADR 0033](0033-shipped-did-not-mean-shipped.md), which
gave the order a `DISPATCHED` state; a refused cancellation now resolves forward rather than
returning to `SHIPPED`. The rest of that list stands.*
