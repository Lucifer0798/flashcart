# 0041 — The alert that forgot

**Status:** Accepted · **Date:** 2026-10-03 · **Phase:** post-roadmap · **Amends [ADR 0031](0031-when-nobody-answers.md)**

## Context

[ADR 0031](0031-when-nobody-answers.md) gave a refused refund a bounded retry and, past the cap, a
critical alert. It then declined, on purpose, to give anybody a button:

> Rejected, because payment deliberately has no HTTP endpoint that moves money — "how could this
> customer have been charged" has a one-word answer, and adding a way to send money out by request
> would spend that property on a convenience.

and recorded the consequence: *the next step is outside this platform — but it does mean the critical
alert resolves only by hand.*

That decision stands, and nothing here reverses it. What it left behind was two smaller things that
together made the escalation path worse than it looked.

**There was no supported way to tell the platform the money had been paid.** Somebody refunds the
customer through the provider's console or by transfer. The payment row still says `REFUND_FAILED` and
still claims the money is owed, and the only way to correct it was an `UPDATE` typed into psql — with
no record of who typed it, or why.

**The alert did not need correcting, because it forgot by itself.** `RefundAbandoned` was:

```
increase(flashcart_payment_refunds_abandoned_total[30m]) > 0
```

That fires at the moment of giving up and resolves thirty minutes later, **whether or not anybody has
paid the customer.** "Resolves only by hand" was not true: it resolved on its own, and on a dashboard
an alert that went quiet because time passed is indistinguishable from one that went quiet because
somebody dealt with it. The one critical alert about money the platform owes had the property that
ignoring it worked.

A rule test makes the point exactly. Feed it one abandoned refund that is never settled:

| | 5 min | 115 min |
|---|---|---|
| old rule | firing | **silent** |
| new rule | firing | firing |

## Decision

**Record the settlement, and alert on what is owed now rather than on the moment of giving up.**

### A record, not a button

```
POST /api/v1/payment/_refunds/{orderNumber}/settled-outside   {"reference": "BANK-TX-0042"}
```

moves the payment from `REFUND_FAILED` to a new terminal `REFUNDED_OUTSIDE`, with the reference, the
time, and the operator's token subject in `refund_settled_by`. It sends nothing to the provider.
ADR 0031's property survives intact: no HTTP request to this service can make money leave it.

A CHECK constraint requires both the reference and the operator on every `REFUNDED_OUTSIDE` row —
enforced in the database too, because the likeliest way for this status to be written badly is the
hand-typed `UPDATE` it replaces.

### Only once the retry job has given up

The one way this could do harm is by paying a customer twice: somebody transfers the money by hand,
then the retry job's next attempt succeeds at the provider. So the update is a single conditional
statement whose predicate is `status = 'REFUND_FAILED' and refund_attempts >= max_attempts` — exactly
the rows the retry claim excludes. The two can never both hold the same payment. Below the cap the
answer is `409 REFUND_NOT_ABANDONED`, which says how many attempts remain.

Once recorded, the payment is no longer refundable, so a retry or a late `RefundPayment` command finds
nothing to do.

### `REFUNDED_OUTSIDE`, not `REFUNDED`

The two are reconciled against different things. A provider refund is on the provider's statement under
the provider's reference; this one is wherever it was actually paid from, under a reference a person
typed. Folding them together would make the provider statement disagree with the table for reasons
nobody could see. For the same reason no `PaymentRefunded` is published: that event says the provider
reversed the capture.

### Operator-only

The path is listed nowhere in the filter, so default-deny makes it `OPERATOR`. None of
[ADR 0038](0038-one-role-did-too-much.md)'s narrower roles fits. `SUPPORT` reads customers' data; this
clears an alert about money, and the cost of doing it wrongly is that a customer who was never paid
stops being anybody's problem. The customer owed the money is refused too, and a test says so.

### The alert watches the rows

A gauge, `flashcart_payment_refunds_abandoned_outstanding`, counts `REFUND_FAILED` rows at the cap.
`RefundAbandoned` is now `max(...) > 0`: it fires while any customer is owed money nothing is trying to
pay, and resolves when the last one is recorded as settled — not before. Its description now says how
to resolve it.

The gauge is refreshed every minute and immediately after a settlement, from a number the service
holds rather than a query on every scrape: a scrape that waits on the database fails when the database
is the problem. If the count fails, the gauge **keeps its last value**. Dropping to zero would resolve
the alert that most needs to stay firing.

The abandon counter stays, as a panel: it answers "how often does the platform give up", which is a
different question from "who is owed money now".

## Verified

- **Rule tests** (`promtool test rules`): the new rule fires at 5 and at 115 minutes for an unsettled
  refund and is silent after settlement; the old rule, given the same input, is silent at 115.
- **Nine integration tests**, and each guarantee was broken in turn to prove a test fails:

| mutation | caught by |
|---|---|
| settling allowed below the cap | `REFUND_NOT_ABANDONED` expected, `200` returned |
| gauge not refreshed on settlement | gauge unchanged after settling |
| a second, different reference accepted | `REFUND_ALREADY_SETTLED` expected |
| `SUPPORT` admitted by the filter | `403` expected |
| count includes refunds still being retried | a refusal at attempt 1 counted as abandoned |

The last one **survived** the first round. Every test measured the count as a difference before and
after settling, and settling moves the wrong count by exactly as much as the right one. The test that
catches it asserts what the count must *not* include: a refusal the job is still retrying would
otherwise page critically for a problem that is about to fix itself.

## Alternatives considered

**A button that retries the refund.** Considered first and rejected, for ADR 0031's reason. It would
also mostly not work: the retry job's own documentation notes that most refusals are permanent, which
is why it gives up at all.

**Keep the counter alert and lengthen its window.** Moves the moment the alert forgets; does not stop it
forgetting.

**Alert on the gauge, but leave settlement as SQL.** Makes the alert honest and makes resolving it
require psql access and leave no record. The alert would be right and the fix would be worse.

**Reuse `REFUNDED`.** See above: it would make the provider's statement and the table disagree.

## Consequences

**Good.** The critical alert means "somebody is owed money right now" and stays on until that stops
being true. Resolving it leaves a record naming who, when and against what reference.

**A payment has a seventh status**, and `PaymentStatus.isRefundable()` excludes it.

**Still open.**

- Production accounts still get `OPERATOR`; the load harness still signs in as the operator.
- Whether a customer may read their own access log, and the operator-scoped view
  ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
- No alert rule has a test in CI. The ones above were run by hand.

*Every rule now has tests in CI, and an alert for a service that stops being scraped, by [ADR 0042](0042-every-alert-went-quiet-together.md). That record also found this alert still resolved while the money was owed, whenever payment itself was down.*
