# 0037 — The last one unargued

**Status:** Accepted · **Date:** 2026-09-29 · **Phase:** post-roadmap · **Completes [ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md), [ADR 0035](0035-nothing-behind-the-check.md) and [ADR 0036](0036-anyone-could-set-the-price.md)**

## Context

Six services expose HTTP. Five are now default-deny: inventory, payment and shipping since
[ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md), the order service since
[ADR 0035](0035-nothing-behind-the-check.md), and catalog since
[ADR 0036](0036-anyone-could-set-the-price.md).

The user service was the last, and it was left out of all three for a reason that sounded good every
time: it is the sign-in surface, so of course some of it is public, and the rest reads
`Authorization` for itself. Probably fine.

**"Probably fine, unargued" is the sentence that preceded every one of the previous omissions.** The
order service was passed over because it already authenticated somewhere. Catalog was passed over
because it reads as a content service — and it turned out anyone could reprice a product and choose
what they paid. [ADR 0028](0028-looking-is-not-acting.md) found the same shape in operator order
access. None of those was a decision; each was an assumption nobody had written down.

So this record exists to settle the question rather than to keep answering it the same way.

## What the audit found

Five endpoints, and the surface is genuinely small:

| | |
|---|---|
| `POST /api/v1/users` | register — must be public |
| `POST /api/v1/users/sessions` | sign in — must be public |
| `GET /api/v1/users/me` | reads `Authorization` |
| `PATCH /api/v1/users/me` | reads `Authorization` |
| `POST /api/v1/users/me/addresses` | reads `Authorization` |

Nothing was reachable that should not have been. This service is not catalog.

**One thing was not quite as advertised**, and it took removing the filter again to see it. An
anonymous `PATCH /api/v1/users/me` did not answer `401`. It answered **`400`**, because the request
body is bound and validated before the handler reads the token — so an unauthenticated caller was
told their body was malformed rather than that they were not signed in. `GET /me` answered `401`
correctly, which is why the difference is easy to miss: the read path behaves, and the write paths
answer a question nobody asked.

That is not an exposure. It is a caller learning something about validation before being refused, and
it is the kind of ordering that is invisible until the refusal moves in front of it.

## Decision

**Give it the same filter, with register and sign-in public by exact path and method.**

| | |
|---|---|
| public | `POST /api/v1/users`, `POST /api/v1/users/sessions`, `/api/v1/user/_info`, `/actuator/**` |
| signed in | `GET /api/v1/users/me`, `PATCH /api/v1/users/me`, `POST /api/v1/users/me/addresses` |
| operator | everything else |

**The two public POSTs are the reason this service is different from the other five.** You cannot
present a token to obtain your first token, so these are the only writes in the platform that must
stay unauthenticated. They are listed by exact path and method, so a future
`POST /api/v1/users/anything` inherits nothing from them.

The `/me` paths sit in the signed-in category, which `OperatorFilter`'s own documentation calls the
dangerous one: passing the filter is not the whole check, and each handler must still resolve the
caller. Here that is unusually easy to get right, because the path names the caller rather than a row
id — there is no "whose is this" question to answer, which is exactly why the ownership bugs this
platform has found were all somewhere else.

## Why do it at all, given nothing was open

For the direction of the list, which is the entire argument of ADR 0022:

> A service that lists what to protect forgets a new endpoint and ships it open; a service that lists
> what to expose forgets a new endpoint and ships it closed.

This service has five endpoints. The sixth would have shipped open, checked only if its author
remembered — which is precisely what happened to the order service and to catalog.

**And the refusal now happens earlier**, which fixes the `400` above as a side effect: the filter
answers before the body is bound, so an anonymous `PATCH /me` is refused as `401` rather than
critiqued as `400`.

## Alternatives considered

**Leave it, on the grounds that nothing is open.** True today, and true of catalog until somebody
checked. The cost of being wrong here is a future endpoint shipping open; the cost of the filter is a
rule list of seven lines.

**Fix the `400` in the controller instead**, by reading the token before binding the body. Narrower,
and it addresses the symptom rather than the ordering — every future write endpoint would have to
remember the same thing, which is the class of mistake default-deny exists to stop needing to
remember.

**Make `/me` operator-only and give shoppers a separate service.** Nobody suggested this and it is
worth recording as rejected: `/me` is the one place a customer's own token is exactly the right
credential, and splitting it would invent a boundary to protect something that is already protected.

## Consequences

**Good.** Every HTTP surface in the platform is now default-deny, and the question that preceded four
separate omissions has an answer rather than an assumption.

**An anonymous write to `/me` now reads `401` rather than `400`.** A small honesty improvement, and
the only externally visible behaviour change.

**One test in this suite fails if the filter is removed and the rest do not.** That is the
discrimination worth having: `unlistedPathsAreClosed` is the property the filter adds, and the `/me`
tests largely pass without it because the handlers do their own work. It is also how the `400` was
found — the mutation failed one more test than predicted, and the extra one was telling the truth.

**Still open.**

- Whether repricing deserves its own role rather than reusing `OPERATOR`
  ([ADR 0036](0036-anyone-could-set-the-price.md)).
- Whether a customer may read their own access log, and the operator-scoped view
  ([ADR 0034](0034-who-read-my-data.md)).
- Cancelled paid orders do not return their units to stock
  ([ADR 0030](0030-cancelling-a-paid-order.md)); an abandoned refund has no button
  ([ADR 0031](0031-when-nobody-answers.md)); the publisher still stores a hard-coded sampled flag.
- Catalog has no `_info` endpoint, alone among the services.
- Per-service ports remain published, which is ADR 0022's decision rather than an oversight.
