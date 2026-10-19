# 0052 — Forgetting an email, safely

**Status:** Accepted · **Date:** 2026-10-19 · **Phase:** post-roadmap · **Builds on [ADR 0017](0017-outbox-and-processed-events.md), [ADR 0048](0048-the-order-tells-its-own-story.md) and [ADR 0051](0051-support-can-see-what-we-told-them.md)**

## Context

The user service's `notices` table holds a row for every email the platform has meant to send. Each row
has a customer id, an order number and what the email said, down to the reference an outside refund was
paid under ([ADR 0050](0050-the-emails-that-were-not-sent.md)). The outbox and `processed_events` have
been pruned since [ADR 0017](0017-outbox-and-processed-events.md). Nothing ever deleted a notice, so the table
kept all of that for every customer, for good, and grew with every order.

Deleting a notice is not like deleting a published outbox row. **A notice is also what stops the same
email being sent twice.** Its key (`refunded:<order>`, `order-confirmed:<order>`, …) turns a repeated
announcement into a no-op. Delete the row and that email can be sent again. The question is when that
can no longer happen.

## Decision

### The shelf life makes it safe, and it needs every event dated by its fact

[ADR 0048](0048-the-order-tells-its-own-story.md) gave every email a shelf life: a notice arriving more
than `flashcart.notices.max-age` (a day) after its event's `occurredAt` is skipped as stale, not sent.
So a notice is safe to forget once its event is older than the shelf life. Any later copy of the same
announcement is then already stale. That holds for a Kafka redelivery, which keeps the original
`occurredAt`.

It did not hold for one re-publish. For a repeated refund command on an already-refunded payment,
payment re-published `PaymentRefunded` stamped *now*. `DomainEvent` documents `occurredAt` as "when the
fact happened, not when it was published", and this broke that. Until now the notice key hid it. After
pruning, a late duplicate command would have emailed "we have refunded" a second time. **Payment now
stamps the re-publish with the refund's own time**, through a new `EventMetadata.of(type, aggregate,
occurredAt)` for re-announcing a fact.

Every other emailed event is published once, on its order's state transition or with the settlement
that wrote it, so the rule holds for all of them.

### Prune finished notices after 90 days, measured from the event

`NoticeRetention` runs hourly and deletes **`SENT` and `SKIPPED`** notices whose `occurred_at` is older
than `flashcart.notices.retention`. The default is `P90D`.

- **Never `PENDING`.** A pending notice is an email still owed. The sender skips it as stale on its
  own terms, and then it may be deleted.
- **Measured from `occurred_at`**, not from when it was sent. That is the same clock the shelf life
  uses, so "older than the window" implies "past the shelf life" with no gap between the two.
- **Ninety days**, because support's read ([ADR 0051](0051-support-can-see-what-we-told-them.md)) is
  only as good as what is kept. Ninety days covers what a customer is still likely to ask about an
  email.
- **A window no longer than the shelf life is refused at startup.** It would let an email be sent twice,
  and whoever set it should learn that from the deploy, not from a customer. It is not clamped and not
  a warning.
- **On by default**, unlike the audit log ([ADR 0026](0026-deleting-an-audit-record-is-a-decision.md)).
  An audit row is evidence that is most valuable long after it was written. A notice is the platform's
  working copy of a message the customer already has in their inbox, and keeping it for ever is a cost
  with no matching benefit.
- **Batched**, 500 rows a batch and up to 20 batches a sweep, so a first run against a long backlog
  does not hold up the sender. It is counted (`flashcart.user.notices.pruned`) and logged.

An index on `occurred_at` for finished rows keeps the sweep from scanning the table.

## Verified

- `NoticeIT`:
  - an old `SENT` and an old `SKIPPED` notice are deleted;
  - a 400-day-old `PENDING` one and an 89-day-old `SENT` one are kept;
  - a 7-row backlog clears in one sweep at a batch size of 2;
  - a window equal to the shelf life is refused;
  - the case that matters: a refund emailed 100 days ago, its notice then pruned, re-published with its
    own date, is skipped as `STALE` and sends nothing. The same re-publish dated now would have been
    emailed again, which the test also shows.
- `PaymentRefundIT`: a repeated refund command re-publishes `PaymentRefunded` dated with the refund's
  `refundedAt`, not the time of the repeat.
- Live, on the compose stack, with the existing database:
  - V8 applied;
  - with `NOTICES_RETENTION=PT12H` the user service refused to start, naming both settings and why;
  - with the sweep's first run brought forward, it deleted exactly one shopper's three emails, which had
    been backdated 100 days, and left the other 27 alone. `flashcart_user_notices_pruned_total` read 3.
- Mutations, all 5 caught: pending notices pruned too; the window ignored; one batch per sweep; a window
  equal to the shelf life allowed; the re-published refund dated now.

## Consequences

**Good.** The table is bounded, and nothing about a customer's emails outlives the window. The fix to
payment makes the re-published refund true about when it happened, whoever reads it.

**Accepted.** After ninety days, support's read no longer shows an email. "Did you email me in March?"
asked in October has no answer, and that is the price of not keeping it.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- `compose.yaml` does not pass `TRACE_SAMPLING` to the services ([ADR 0049](0049-loose-ends.md)).
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
