# 0035 — Nothing behind the check

**Status:** Accepted · **Date:** 2026-09-27 · **Phase:** post-roadmap · **Closes the asymmetry found by [ADR 0034](0034-who-read-my-data.md)**

## Context

[ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md) put a default-deny `OperatorFilter` in
front of inventory, payment and shipping, and argued the direction carefully:

> A service that lists what to protect forgets a new endpoint and ships it open; a service that lists
> what to expose forgets a new endpoint and ships it closed. Only one of those failure modes is safe,
> and both are equally likely.

**The order service never got one.** It was passed over because it already authenticated in every
handler, while the other three authenticated nowhere — so the filter went where the hole was, and the
service that had *some* protection was left with only that. Nobody recorded it as a decision.

[ADR 0034](0034-who-read-my-data.md) turned it up by accident. Adding an operator-only audit endpoint
to all three of order, payment and shipping produced an endpoint that was closed by the *arrangement*
in two services and closed only by its own code in the third. Measured rather than argued — with the
operator check inside `OperatorAccessLogReader` disabled:

| | |
|---|---|
| payment's audit suite | **14 pass** — the filter refuses before the code runs |
| order's `customerCannotReadTheAccessLog` | **fails** — nothing else refuses |

## The matcher had been deciding which rules were writable

`OperatorFilter.matches` understood a star only at the *end* of a rule: `/**`, `/*`, `**`, and
otherwise string equality. Two of the order service's endpoints do not have that shape:

```
GET  /api/v1/orders/{orderNumber}/history
POST /api/v1/orders/{orderNumber}/cancel
```

A rule of `GET /api/v1/orders/*/history` ends in `/history`, so it fell through to `path.equals(rule)`
and matched **nothing** — and in a default-deny filter matching nothing means the endpoint is
operator-only. Listing those two paths as signed-in would have produced a configuration that read
correctly, passed review, and locked customers out of their own order history.

That is worth naming beyond this change: **the matcher's shape had been quietly constraining the
rules anybody could write.** Payment's and shipping's rule sets are all trailing-star shaped, which
looked like a house style and was partly a limitation.

## Decision

**Extend the matcher with a mid-path segment star, then give the order service the filter.**

### The extension is additive by construction

The new branch is added *after* every branch that existed before it, so no rule that already matched
something can be diverted into it — the four original cases win exactly as they did. A rule with a
mid-path star previously reached the equality check and matched nothing, because paths do not contain
stars. So the branch can only add matches and never remove one.

That argument is what makes this safe to do to a security primitive two other services already
depend on, and it is stronger than a test sweep: it does not rely on having thought of every existing
rule. The single-star case is re-asserted anyway, because it is the one that keeps the inventory
movement ledger closed ([ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md)) and the one
that would hurt to lose.

A star matches exactly one segment and never an empty one — `/api/v1/orders//history` is not a path
that named an order.

### The order service's three categories

| | |
|---|---|
| public | `/api/v1/order/_info`, `/actuator/**` |
| signed in | `POST /api/v1/orders`, `GET /api/v1/orders`, `GET /api/v1/orders/*`, `GET /api/v1/orders/*/history`, `POST /api/v1/orders/*/cancel` |
| operator | everything else, which today is `/api/v1/order/_access-log` |

`/actuator/**` is public because compose health-checks it, and a container that cannot answer is a
container that restarts forever.

**The middle list is the dangerous one**, as `OperatorFilter`'s own documentation says: passing the
filter is not the whole check there. Every path in it has a handler that resolves ownership, and
`OrderController` returns **404 rather than 403** for somebody else's order so an order number cannot
be probed ([ADR 0021](0021-the-client-does-not-say-who-it-is.md)). The filter does not replace those
checks and could not — a path does not say who owns what is behind it.

The existing order suite is the regression check on this classification. Every test in it either
places, reads, lists, cancels or audits an order as a specific caller, so a rule that is wrong in
either direction fails something.

## Alternatives considered

**Add the filter without touching the matcher.** What "add default-deny to the order service" sounds
like, and it would have shipped a service where reading your own order history returned 403. The
configuration would have looked right, which is the failure this ADR is about.

**Give the matcher a full glob or hand it to Spring's `PathPattern`.** More capable and a larger
surface to reason about. The cases this filter needs are prefix, one segment, and one segment in the
middle; a general matcher would bring precedence questions nobody here has to answer, on the one
component where being slightly wrong is invisible until somebody probes it.

**Rewrite the two endpoints so their paths are trailing-star shaped**, avoiding the matcher change.
Rejected on the tail wagging the dog: `/orders/{n}/history` is the right URL, and bending resource
design around a matcher's limitation is how the limitation becomes permanent.

**Leave it and rely on handler checks.** It works today, and this is the second time that has been
tested by accident rather than on purpose. The audit endpoint was closed only because somebody
remembered; ADR 0022's argument applies to this service no less than the others.

## Consequences

The same mutation, run before and after, is the evidence this worked. Disabling the operator check
inside `OperatorAccessLogReader`:

| | order's `customerCannotReadTheAccessLog` |
|---|---|
| before this change ([ADR 0034](0034-who-read-my-data.md)) | **fails** — nothing else refused |
| after it | **passes** — the filter refuses first, as it already did in payment |

**Good.** Every service that exposes customer data is now default-deny. A new endpoint in the order
service is closed until it is listed, which is the property ADR 0022 wanted and only three of four
services had. The audit endpoint from ADR 0034 now has two layers rather than one.

**A rule shape that used to match nothing now matches something**, which is the point, and is exactly
why the extension's non-regression argument is structural rather than empirical.

**Two checks now guard the same paths** — the filter's category and the handler's ownership test.
That is not redundancy: they answer different questions, and the filter cannot answer the second one.

**Still open.**

- Whether a customer may read their own access log, and the operator-scoped view of it
  ([ADR 0034](0034-who-read-my-data.md)).
- Cancelled paid orders do not return their units to stock
  ([ADR 0030](0030-cancelling-a-paid-order.md)); an abandoned refund has no button
  ([ADR 0031](0031-when-nobody-answers.md)); the publisher still stores a hard-coded sampled flag.
- The **catalog** and **user** services have no `OperatorFilter` either. Catalog is mostly public by
  design and user is the sign-in surface, so neither is the same omission — but neither has been
  argued through the way these four now have, and that is worth doing rather than assuming.
- Per-service ports remain published, which is
  [ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md)'s decision rather than an oversight.

*Catalog was since given the same filter by [ADR 0036](0036-anyone-could-set-the-price.md), which found it had no authentication of any kind. Only the user service is left, and it is the sign-in surface.*
