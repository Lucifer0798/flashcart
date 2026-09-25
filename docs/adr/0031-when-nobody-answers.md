# 0031 — When nobody answers

**Status:** Accepted · **Date:** 2026-09-22 · **Phase:** post-roadmap · **Finishes work left open by [ADR 0030](0030-cancelling-a-paid-order.md)**

## Context

[ADR 0030](0030-cancelling-a-paid-order.md) made cancelling a paid order a question put to shipping,
and named two ways the answer could fail to arrive. Both were left open in its own words:

> **A refused refund has no retry path.** `REFUND_FAILED` is refundable by design, but nothing
> re-sends the command; the alert brings a human who has no button to press.
>
> **A cancellation request has no sweeper.** If shipping's answer is lost, the order sits in
> `CANCELLATION_REQUESTED`. There is a partial index for finding them and nothing that looks.

They are the same shape — something is waiting for an answer that is not coming — and they are
opposite in what can be done about it, which is the interesting part.

## Decision

**Two backstops, and only one of them is allowed to conclude anything.**

### The cancellation backstop can only repeat the question

`CancellationReconciliationService` finds orders that have been in `CANCELLATION_REQUESTED` longer
than `cancellationTimeout` and **re-sends `CancelShipment`**. It does not resolve them.

That is the whole design, and it is a deliberate departure from the reconciler beside it.
`OrderReconciliationService` *reaches a conclusion*: the order mirrors `reservationExpiresAt`, so
when no expiry event arrives it can decide for itself that the hold lapsed and cancel. This one holds
no equivalent fact. Whether the parcel has left is shipping's alone — that is exactly what ADR 0030
decided — so timing out to a conclusion would be inventing the answer:

| Timing out to | What it would do |
|---|---|
| `CANCELLED` | refund parcels already in transit |
| `SHIPPED` | keep the money for goods still on a shelf |

Both are the mistake ADR 0030 exists to prevent, reached by waiting instead of by deciding. A
backstop that cannot decide should re-ask, not guess.

Re-asking is safe because shipping's handler is idempotent in all three directions: an
already-cancelled consignment re-publishes its cancellation, a dispatched one re-publishes its
refusal, and one still in `CREATED` is cancelled as the first command should have done.

**A re-ask touches the order row.** Nothing about the order changes — it is still waiting — so no
`@UpdateTimestamp` fires on its own, and without an explicit touch the row would keep its original
timestamp, stay permanently past the cutoff, and be re-asked on *every tick*. One stuck order would
become a command every fifteen seconds for as long as shipping stayed down. The touch restarts the
clock the query measures against, so the rate is one re-ask per order per timeout. `version` is
deliberately left alone: this is a note about when somebody last chased the order, not a change to
it, and bumping the version would fail a concurrent write over a timestamp.

### The refund retry can act, so it does — up to a point

`RefundRetryService` re-attempts `REFUND_FAILED` payments after a delay, and **gives up after
`maxAttempts` refusals**.

The cap is the decision. Most refusals are permanent — a capture too old to reverse, an account since
closed — and retrying those forever produces an alert that is always firing, which is an alert nobody
reads. ADR 0018's rule applies to the thing an alert describes: "some refunds are failing" has to be
able to become false, or it stops carrying information.

**Giving up on retrying is not deciding the money is not owed.** The payment stays `REFUND_FAILED`
and `isRefundable()` still returns true, so a later command still works. What stops is this job's
automatic attempts. That is why the cap lives in the job's configuration and not in
`PaymentStatus.isRefundable()` — the domain's answer to "can this be refunded" must not depend on how
many times a scheduled job has already tried.

The attempt count is incremented in `Payment.refundFailed`, so it counts *refusals* rather than
tries. A refund that eventually succeeds leaves the count where it was, which is the honest record:
it says how many times the provider said no before it said yes.

### No endpoint to press instead

The obvious alternative to a retry job is a button for an operator. Rejected, because payment
deliberately has no HTTP endpoint that moves money — "how could this customer have been charged" has
a one-word answer, and adding a way to send money out by request would spend that property on a
convenience. The job reaches `PaymentService.refund` by the same path a command does.

### Metrics, and an escalation

Following [ADR 0018](0018-metrics-answer-questions-the-logs-cannot.md) and
[ADR 0029](0029-an-operator-read-was-a-silence.md), the question for each counter is whether the
state it describes is wrong *on its own terms*.

| Metric | Alerts | Because |
|---|---|---|
| `flashcart_order_cancellations_unanswered_total` | after 10m sustained | one re-ask is a lost message, which is what a backstop is for. A rate that will not fall to zero means nothing is answering at all. |
| `flashcart_payment_refunds_total{outcome="refused"}` | yes (from ADR 0030) | a refusal, now with a retry in progress behind it |
| `flashcart_payment_refunds_abandoned_total` | **critical** | nothing further will happen on its own |

The last one is counted at the moment the cap is reached, not every time a capped payment is seen —
which is why the claim query excludes them. It has to mean "the platform gave up on another one", not
"the platform is still holding some".

## Alternatives considered

**Time a stalled cancellation out to `CANCELLED`.** The intuitive reading of "sweep up stuck
orders", and it quietly reintroduces exactly the bug ADR 0030 removed: it pays out for parcels that
may already have been dispatched. The difference is that this version would do it after a delay,
which makes it harder to notice rather than less wrong.

**Retry refunds forever.** Rejected on the alert becoming permanent, above. A cap plus a louder
alert when it is hit carries more information than an unbounded retry with a quieter one.

**Put the cancellation backstop in `OrderReconciliationService`.** It is already "the backstop for
orders", so this looked like the natural home. Separated because the reasoning is opposite — one
concludes, one may not — and that distinction is the thing most likely to be lost when somebody
later adds a third case to a class whose comment says it decides.

**Give the refund retry an exponential backoff.** More correct in principle and not worth the column
and the arithmetic here: a fixed delay with a small cap spans the same window, and `updated_at`
already carries "when we last tried" without a field that has to be kept true by hand.

## Consequences

**Good.** Neither failure leaves something permanently silent. A lost shipping answer costs one
re-asked question per timeout instead of an order stuck forever, and a refused refund is attempted
several more times before anybody is asked to look at it.

**Neither backstop can fix a downstream that is down.** Re-asking a shipping that is not listening
achieves nothing, and retrying a provider that will always refuse achieves nothing. Both jobs are
built to make that *visible* rather than to solve it, which is the same position
`PaymentReconciliationService` already takes about a provider it cannot query.

**Still open** *(at the time of writing — see below)*.

- **`OrderStatus.DELIVERED` remains unreachable.** `ShipmentService.deliver` publishes no event and
  the saga has no handler, so no order has ever reached the successful terminal state that the
  README and the architecture diagram both draw. Next.
- **An abandoned refund still has no button.** By the argument above that is correct — the next step
  is outside this platform — but it does mean the critical alert resolves only by hand.
- Cancelled paid orders still do not return their units to stock (ADR 0030), per-service ports remain
  published, the publisher still stores a hard-coded sampled flag, and `operator_access_log` still
  has no reader across three databases.

*`DELIVERED` was since made reachable by [ADR 0032](0032-an-order-that-can-finish.md). The abandoned
refund still has no button, and the rest of that list stands.*

*The audit reader was since built by [ADR 0034](0034-who-read-my-data.md): each service exposes its own, since each has its own table.*
