# 0050 — The emails that were not sent

**Status:** Accepted · **Date:** 2026-10-12 · **Phase:** post-roadmap · **Closes items left open by [ADR 0048](0048-the-order-tells-its-own-story.md)**

## Context

[ADR 0048](0048-the-order-tells-its-own-story.md) emailed an order's story from the events the order and
payment services already published. Two things that happen to an order published nothing, so no email
could be sent for them:

- **A refund an operator records as paid outside the platform.** [ADR 0041](0041-the-alert-that-forgot.md)
  added `REFUNDED_OUTSIDE` for a refund the provider refused until the retry job gave up, after which the
  money went back some other way. It deliberately does not publish `PaymentRefunded`, because that event
  says the provider reversed the capture, and it did not. So it published nothing. This is the customer who
  most needs the email: they were told *"the money is on its way back"* when the order was cancelled, then
  the card refund failed five times, and the platform never told them it was finally paid.
- **A shopper cancelling an unpaid order.** `OrderService.cancel` moved the order straight to `CANCELLED`
  and published nothing. Every other path to `CANCELLED` goes through the saga, which announces it. So the
  one cancellation the shopper asked for was the one they were never sent a confirmation for.

## Decision

### Payment announces a settlement made outside the platform

A new event, `RefundSettledOutside`, carries the order, customer, amount, currency and the operator's
reference. It is published through the outbox **in the same transaction as the update that settles the
payment**, and only when that update wrote the row. So:

- a settlement that rolls back announces nothing;
- a refusal (`REFUND_NOT_ABANDONED`, `REFUND_NOT_OWED`, `REFUND_ALREADY_SETTLED`) announces nothing, so the
  customer is never told about money that has not been sent;
- a repeat with the same reference, which ADR 0041 made harmless, is also silent: it writes nothing, so it
  publishes nothing.

It is its own event, not `PaymentRefunded` with a flag. Everything that reads `PaymentRefunded` today takes
it to mean the provider reversed the charge. A flag every consumer must remember to check is how that
meaning would stop being true.

### The user service sends it as the refund email, worded for how the money went

The listener records it under the same key as a provider refund, `refunded:<order>`. An order gets **one
refund email, however the money went back.** If a provider refund somehow followed a manual one, or the
reverse, the second would be dropped rather than tell the customer they were paid twice.

The email says what happened: the card could not be refunded, so the money was sent to them directly,
under a reference. The reference is what their bank statement will show, so it is the one detail that
lets them find the payment.

### Every path to CANCELLED announces it

The direct cancel now publishes `OrderCancelled` with reason **`CANCELLED_BY_CUSTOMER`**, through the same
saga method every other cancellation uses. The reason code is accurate: the endpoint refuses anyone but
the order's owner (ADR 0023), and only an unpaid order reaches this path. A paid one goes to
`CANCELLATION_REQUESTED` and is announced by the saga when shipping answers. The email says *"You
cancelled it before paying, so nothing was charged."*

Only the user service consumes `OrderCancelled`. Inventory learns of a cancellation from
`ReleaseInventory`, which this path already sent when the order held stock, so no stock moves twice.

## Verified

- `RefundSettlementIT`: settling publishes one `RefundSettledOutside` with the order, customer, amount and
  reference; a refused settlement publishes none; a repeat with the same reference publishes nothing more.
- `OrderIT`: cancelling an unpaid order publishes `OrderCancelled` with `CANCELLED_BY_CUSTOMER` and the
  customer's id.
- `NoticeIT`: the outside refund's email says so and gives the reference; a provider refund followed by an
  outside one for the same order sends one email; the customer's own cancellation gets its own sentence.
- All six of these mutations were caught: settlement announcing nothing; announcing whether or not it
  settled; the customer's cancel announcing nothing; the outside refund keyed apart from the provider one;
  the outside refund worded as a card refund; the customer's cancel getting the generic sentence.
- Live, on the compose stack: an unpaid order, held at `CREATED` by stopping inventory and then cancelled
  by its shopper, was emailed *"You cancelled it before paying"*. A `.77` order, which the simulated
  provider will not refund, was cancelled after payment, brought to the attempt cap, and settled with
  `POST /api/v1/payment/_refunds/{order}/settled-outside`. Its shopper got three emails in order:
  confirmed, cancelled, then the refund with its reference. Repeating the settlement added no fourth.
- A new CI step runs that second flow end to end. `NoticeIT` calls the listener directly, so this step
  is the only check that the event really travels payment → Kafka → user. It was run against the local
  stack: it passed, and it failed with the user service stopped.

## Consequences

**Good.** Every way an order ends, and every way its money goes back, now reaches the customer. The
customer who waited longest for their refund is no longer the one who never hears about it.

**Accepted.** The refund email for an outside settlement goes out when the operator records it, not when
the money arrives. The platform knows only that someone says they sent it. The email says it was sent, not
that it has arrived.

**Still open.**

- `compose.yaml` does not pass `TRACE_SAMPLING` to the services ([ADR 0049](0049-loose-ends.md)).
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
