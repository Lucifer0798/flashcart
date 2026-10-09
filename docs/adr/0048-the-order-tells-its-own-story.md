# 0048 — The order tells its own story

**Status:** Accepted · **Date:** 2026-10-09 · **Phase:** post-roadmap · **Builds on [ADR 0046](0046-the-first-message-out.md)**

## Context

[ADR 0046](0046-the-first-message-out.md) sent the platform's first email: a shopper told their SKU was
back. The moments a shopper actually waits on — paid, on its way, arrived, cancelled, refunded — still
reached nobody.

The events for most of them existed, but not in a shape an email could use:

| moment | event | missing |
|---|---|---|
| confirmed | `OrderConfirmed` | nothing |
| dispatched | only shipping's `ShipmentDispatched` | the customer, and it is not the order's event |
| delivered | `OrderDelivered` | nothing — except that it was sometimes never published (below) |
| cancelled | `OrderCancelled` | the customer |
| refunded | `PaymentRefunded` | the customer and the order |

## Decision

**The order service tells the order's story on `flashcart.order.events`; payment tells the refund; the user
service turns both into email through one queue.**

### Events in the shape the reader needs

- `OrderCancelled` carries `customerId`.
- **`OrderDispatched`** is new: the order's own account of its parcel leaving, published once on entering
  `DISPATCHED`. Shipping's `ShipmentDispatched` is about a shipment; this is about an order, and carries
  the customer.
- `PaymentRefunded` carries `orderNumber` and `customerId`. The refund is announced by payment, not the
  order, because the money is payment's: a cancelled paid order is not refunded until payment says so,
  and the refund email must not go out on the cancellation's word.

New fields are appended, and every record ignores unknown properties, so old and new publishers and
consumers interoperate. An `OrderCancelled` from before this change has no customer and is skipped
rather than misdelivered.

### A delivery that was never announced

Writing the dispatched and delivered emails exposed an existing gap. When shipping refuses a cancellation
because the parcel has already left or arrived, the order moves straight to `DISPATCHED` or `DELIVERED`.
When shipping's own `ShipmentDispatched` or `ShipmentDelivered` then arrives, the order is already in that
state, the transition is declined, and nothing is published. **An order delivered via a refused
cancellation never announced its delivery.** Both refusal paths now publish, and because they publish only
on the transition, each order announces each state once whichever way it got there.

### One queue, not one per kind

`back_in_stock_notices` becomes `notices`, and its rows move across. Every guarantee ADR 0046 earned for one
kind now holds for six:

- **once per thing.** The key is what the email is about — `order-confirmed:<order>`,
  `refunded:<order>`, `back-in-stock:<entry>` — so a repeat announcement is a no-op
- **retried until sent.** Backoff to a thirty-minute ceiling, no attempt limit, stop at the first refusal
- **not sent stale.** `send_before` generalises "the hold has lapsed": past it, the email would be false
- **in the order things happened.** New, and the reason the queue needed to know about orders

### In order, by when it happened

A "confirmed" held back by a mail outage must not be overtaken by the "dispatched" behind it. A notice is
not due while an older one for the same order is still pending.

"Older" is the event's **`occurred_at`**, by the publisher's clock — not when this service heard it. Each
event type has its own consumer group, and groups do not wait for each other, so a dispatch can be heard
before the confirmation it follows. A test delivers them in that order and asserts the emails go out the
other way round. Ordering is per order: another order's stuck email holds nothing back.

### A shelf life, found by deploying it

The first live deploy of these listeners **emailed four shoppers that their days-old orders were
confirmed.** Every consumer here starts from the earliest offset — right for the saga, which must not miss
a command — so new consumer groups read the order topic from the beginning and treated its history as news.
In production that would mail every retained event to every customer on the day the feature shipped.

Starting the email consumers at the latest offset instead would trade that for losing whatever is
published between the deploy and the group's first join. The cut belongs on meaning, not position: **every
email now has a shelf life**, `flashcart.notices.max-age` (a day) after the event, past which it is skipped
as stale. It is the same `send_before` the hold already used, so it costs no new mechanism, and it also
covers a mail outage measured in days — a confirmation that arrives after the parcel has is noise.

### What each email says

Plain sentences, for the person reading: no status codes and no internal names. A cancellation is worded
by its reason — sold out, card declined, payment too slow, the per-customer limit reached, or cancelled
after payment with the refund to follow — and an unknown reason gets "you have not been charged", which is
true of every cancellation code except the one that says otherwise.

The first draft matched reasons by substring and filed `CUSTOMER_LIMIT_EXCEEDED` under "sold out", because
it contains `LIMIT`. Telling a shopper an item is gone when they have in fact already bought their share
is a different message and a worse one. Reasons are now matched exactly, and a test checks each code's
wording and that no code ever appears in an email.

## Verified

- **`NoticeIT`, 17 tests**: every back-in-stock guarantee from ADR 0046, plus: a confirmed order emailed
  once across two announcements; each cancellation reason's wording, with no code leaking; the refund's
  amount; one order's emails in sequence despite a retry; sequence by occurrence when heard out of order;
  another order's stuck email not blocking; an old `OrderCancelled` with no customer skipped; an order
  email past its shelf life skipped.
- **`NoticesMigrationIT`** drives Flyway to V5, writes back-in-stock notices in each state the old table
  allowed, migrates to V6 and reads what arrived: states, attempts, `HOLD_LAPSED` as `STALE`, and the hold
  carried into the payload in a form the email parses back. The same migration also ran on a live database
  holding five real notices.
- **`OrderIT`**: `OrderDispatched` on dispatch; `OrderCancelled` carries the customer; a refusal to
  `DELIVERED` and to `DISPATCHED` each announce, once, even when shipping's own event follows.
  **`PaymentRefundIT`**: the refund carries order and customer.
- **Mutations**, each caught: no per-order ordering; sequencing by arrival instead of occurrence; the limit
  code read as sold out; a refusal to `DELIVERED` announcing nothing; no shelf life on order emails; the
  migration dropping the hold.
- **Live**: a paid order cancelled through the real saga produced exactly three emails in Mailpit, in order
  — confirmed, cancelled ("you were charged, so the money is on its way back"), refunded.
- **CI**: the paid-cancellation step now requires exactly three emails for the order, in order: confirmed,
  cancelled, refunded.

## Alternatives considered

**The user service consumes shipping's and inventory's events directly.** It would learn an order's
story from four services and have to know which one to believe. The order already decides that.

**The order service relays the refund.** It would have to consume `PaymentRefunded` for an order already
terminal, only to republish it. Payment is the authority on money; its event now carries enough.

**A table per kind of email.** Each would re-earn once-only, retry and staleness, and none could order
emails across kinds for the same order.

## Consequences

**Good.** A shopper is told when their order is paid, leaves, arrives, is cancelled and is refunded, in that
order, once each, and a mail outage delays rather than loses any of it.

**The user service consumes three topics.** Each listener has its own consumer group, as everywhere.

**Still open.**

- A refund recorded as made outside the platform (`REFUNDED_OUTSIDE`, ADR 0041) publishes no event, so
  sends no email.
- A customer who cancels an unpaid order themselves gets no email; they were there when it happened.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
