# 0039 — Nothing ever ran without OPERATOR

**Status:** Accepted · **Date:** 2026-10-01 · **Phase:** post-roadmap · **Exercises [ADR 0038](0038-one-role-did-too-much.md)** · **Corrected by [ADR 0047](0047-staff-roles-on-the-record.md)**

## Context

[ADR 0038](0038-one-role-did-too-much.md) split one `OPERATOR` that authorised thirty-one endpoints
into `WAREHOUSE`, `CATALOG` and `SUPPORT`, with `OPERATOR` as a superset so the change was additive.
It closed by naming what it had not done:

> **Granting the narrower roles to anything.** The mechanism exists and nothing uses it; the seed still
> grants `OPERATOR`. Issuing a `WAREHOUSE` account to the chaos harness would be the first real
> exercise of the split, and it would prove the boundary the way only a caller that lacks a privilege
> can.

That mattered more than it sounds. Every role test in ADR 0038 minted its own token in-process, and
every harness signed in as the one account holding `OPERATOR` — which satisfies every role. **A rule
scoped to the wrong role would have been masked by that superset in every single check.** The split
was configuration nobody had run against.

## Decision

**Seed one account per role, and give every caller exactly the privileges its work needs.**

`V901__development_role_accounts.sql` adds `warehouse@`, `catalog@` and `support@flashcart.local`
beside the existing operator, sharing its published password — one checked-in credential wearing four
hats rather than four secrets, acceptable here for the reason
[ADR 0024](0024-the-development-operator-is-not-schema.md) gives and nowhere else.

| caller | holds |
|---|---|
| CI stock, reservations | `WAREHOUSE` |
| CI products | `CATALOG` |
| CI reads of another customer's payments and parcels | `SUPPORT` |
| CI wrong-verb probe | `OPERATOR`, deliberately — see below |
| chaos harness | `WAREHOUSE` and `CATALOG`, **no `OPERATOR`** |

`scripts/operator-token.sh` needed no change: it already took `OPERATOR_EMAIL`, so signing in as any
seeded account was a matter of setting it.

### The one `OPERATOR` left in CI is load-bearing

The wrong-verb probe sends `PUT /api/v1/products`, a path no role rule covers, and asserts 405. It must
keep `OPERATOR`, and that is the point: it asserts in passing that **an unlisted path is still
operator-only**, which is the default the whole arrangement rests on. Narrowing it would have removed
the only check that the fallback still exists.

*Not quite the one: CI's k6 load step also passed `$OPERATOR` as the harness token, and this record missed it. It needed only `WAREHOUSE` and holds only that since [ADR 0047](0047-staff-roles-on-the-record.md), which leaves the wrong-verb probe as genuinely the last.*

### `SeededOperatorCheck` had to grow, and this is the part that nearly went wrong

That class warns when the seeded development operator is found in a deployment that did not ask for
it, because its password is published in this repository. It was keyed on **one** UUID.

Adding three accounts with the same published password and leaving the check alone would have meant
three credentials that nothing warns about — in the one class whose entire job is to warn about
exactly that. It now reports every seeded account by email, and a test asserts that a non-operator
account present *alone* is a finding, which the single-id version could not see at all.

## What the exercise found

Run against the current images, each role holds its own work and nothing else:

| | `WAREHOUSE` | `CATALOG` | `SUPPORT` |
|---|---|---|---|
| receive stock | **201** | 403 | 403 |
| create a product | 403 | **201** | 403 |
| read the audit log | 403 | 403 | **200** |
| `PUT /products` (unlisted) | 403 | 403 | 403 |

`OPERATOR` gets 405 on that last row — past the filter, refused by the shared error handler, which is
the fallback intact.

The chaos harness then ran the `redis` scenario end to end holding no `OPERATOR` at all, and reported
`granted=19 refused=21 other=0`. **`other=0` is the assertion that matters**: a 401 or 403 from a
mis-wired token would have landed there, so those forty reservation calls genuinely went through as
`WAREHOUSE`.

### Two false alarms, and why they are worth recording

Docker Desktop stopped during a rebuild. The recovery used `docker compose up -d` without `--build`,
so the stack came back on images predating ADR 0038. That produced, in order:

1. a `401 INVALID_CREDENTIALS` signing in as `warehouse@flashcart.local` — the seed migration was not
   in the running image
2. **every** cell of the matrix above as 403, including the three that should pass — no role rules in
   those images, so everything fell through to `OPERATOR`

The second reads exactly like "ADR 0038's wiring does not work". The tell was in the same run:
`OPERATOR` still got 405 on the unlisted path, so the filter was plainly functioning. A stale image
produces results indistinguishable from a real defect, and the only reliable guard is rebuilding
before believing a live probe.

## Alternatives considered

**A fourth seeded account to act as the chaos harness's shopper.** The harness places orders, which
needs only a signed-in caller — the ownership check resolves the token's own subject. The warehouse
account is as good a shopper as any and cannot touch the catalogue, so a fourth row would have added a
credential to publish for no privilege it needed.

**Leave CI on `OPERATOR` and convert only chaos.** Half the exercise. CI is where the per-call mapping
is densest — stock, products, and reads of another customer's data all in one job — so it is where a
mis-scoped rule was most likely to be hiding.

**Make the roles exclusive and drop `OPERATOR` from the seed.** The end state, still not this change.
Something has to hold the fallback for unlisted paths, and the honest sequence is to exercise the
narrow roles first and discover what actually needs `OPERATOR` before removing it.

## Consequences

**Good.** The split is no longer a claim. Two harnesses run on least privilege, and a rule scoped to
the wrong role now fails loudly in CI rather than being absorbed by a superset.

**Four published credentials instead of one**, all under the `demo` profile and all now covered by
`SeededOperatorCheck`.

**Still open.**

- **Production accounts still get `OPERATOR`.** Nothing here changes what a real deployment grants;
  it makes the narrow roles demonstrably usable, which is the prerequisite.
- The load harness still signs in as the operator. It only places orders and reads its own, so a
  shopper account would be the honest fit — smaller than it sounds and not bundled here.

  *Wrong about what the harness does: it seeds stock and reserves directly against inventory, which is warehouse work, not a shopper's. It signs in as `WAREHOUSE` since [ADR 0047](0047-staff-roles-on-the-record.md).*
- Whether a customer may read their own access log, and the operator-scoped view
  ([ADR 0034](0034-who-read-my-data.md)).
- Cancelled paid orders do not return their units to stock
  ([ADR 0030](0030-cancelling-a-paid-order.md)); an abandoned refund has no button
  ([ADR 0031](0031-when-nobody-answers.md)); the publisher still stores a hard-coded sampled flag;
  catalog has no `_info` endpoint.
