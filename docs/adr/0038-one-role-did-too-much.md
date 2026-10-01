# 0038 — One role did too much

**Status:** Accepted · **Date:** 2026-09-30 · **Phase:** post-roadmap · **Follows from [ADR 0036](0036-anyone-could-set-the-price.md)**

## Context

Closing the last of the open write surfaces made this worse, correctly.

Before [ADR 0036](0036-anyone-could-set-the-price.md), catalog's eleven write endpoints needed no
account at all. Requiring an operator was the right fix and it folded them into the *same* role that
receives stock, adjusts a ledger, dispatches a parcel and reads an audit trail. Counted after that
change:

| service | endpoints | operator-only |
|---|---|---|
| catalog | 21 | 11 |
| inventory | 17 | 15 |
| shipping | 7 | 3 |
| order | 7 | 1 |
| payment | 5 | 1 |
| user | 6 | 0 |
| | | **31** |

**One role authorised thirty-one endpoints across six services.** The account a warehouse needs in
order to mark a parcel dispatched could also reprice every product in the shop and read any customer's
record of who had looked at their data.

The token already carried a role *list* — `issue(userId, email, List<String> roles)` — and the user
row already stored roles as a column that somebody grants with a deliberate `UPDATE`
([ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md) argues that is the right friction). What
was missing was anywhere to say that a *path* needed something narrower than `OPERATOR`.

## Decision

**Three named roles, and `OPERATOR` is a superset of all of them.**

| role | what it is for | endpoints |
|---|---|---|
| `WAREHOUSE` | stock, allocations, reservations, dispatch and deliver | 17 |
| `CATALOG` | products, categories, flash sales | 11 |
| `SUPPORT` | reading another customer's data, and who has read it | 3 + the code-level capability |

`OperatorFilter` gains a fourth category: paths keyed by the role that is sufficient for them. A
request passes if the token carries that role, **or** `OPERATOR`.

### Why `OPERATOR` implies everything, and what that costs

It makes the change additive rather than a migration. Every token already issued keeps working; CI, the
chaos harness, the load harness and the seeded development operator are untouched; nothing had to be
re-granted before this could ship.

**It also means this change removes nobody's privileges.** Least privilege begins when somebody is
granted `WAREHOUSE` instead of `OPERATOR`, which is an `UPDATE` against a user row. That is worth
stating plainly rather than letting the ADR imply the platform is now least-privileged: **this makes
separation possible; it does not impose it.**

The alternative — making the roles exclusive — would have been a migration with a flag day, and the
first casualty would have been the checked-in development operator that every harness signs in as.
Trading a real behaviour change for a flag day, on the component where being subtly wrong is invisible
until somebody probes it, is not a trade worth making in the same change that introduces the concept.

### The new category can only widen, never narrow

Role-scoped rules are checked *after* the `OPERATOR` check and *in addition* to it. Everything not
listed in any of the four lists still requires `OPERATOR`, exactly as before. So:

- adding a rule here cannot accidentally close something
- forgetting to add one leaves the path operator-only, which is the safe direction

That is the same argument the other three categories rest on, and it is why this was safe to do to a
primitive six services depend on.

### Named for the capability, not the role

The filter can express "this path needs `SUPPORT`". The code cannot always: `CustomerDataAccess`
decides whether a caller may read a row belonging to somebody else, and that check lives in
`flashcart-common` rather than in a path list.

That check is now `caller.maySeeAnotherCustomer(authorization)` — **not** `isSupport(...)`. A method
named after a role invites the next reader to add a second one beside it and check both at every call
site, which is how a permission model decays into role names scattered through handlers. The
capability is what a handler actually wants to ask; which roles satisfy it is free to change without
touching one of them.

**This pairing is the part most likely to go wrong quietly.** Had the filter admitted `SUPPORT` while
`OperatorAccessLogReader` still demanded `OPERATOR`, the refusal would have come from one layer deeper
and looked identical from outside — a 403 either way. A test asserts the 200, so it is covered rather
than assumed.

## Alternatives considered

**Exclusive roles, with `OPERATOR` removed.** The end state, and not the first step. See above.

**A permission string per endpoint** (`catalog:write`, `stock:receive`) rather than three coarse
roles. More precise and more to get wrong: thirty-one endpoints would become thirty-one strings to
keep in step with a rule list, and the interesting boundary here is between three *jobs* — warehouse,
merchandising, support — not between individual endpoints. Three roles can be held in a reader's head;
thirty-one cannot.

**Role hierarchy in the token** (`OPERATOR` expanded to the three at issue time, in the user service).
Equivalent in effect and worse to debug: a token would then claim roles nobody granted, and
`select roles from users` would stop matching what the token says.

**A separate admin service or gateway-level authorisation.** Both move the decision away from the
service that owns the data, which is the arrangement ADR 0022 established deliberately. Per-service
ports are published, so a gateway-only check is not a check.

## Consequences

**Good.** A warehouse account can be issued that cannot reprice the shop, and a support account that
can read an audit trail but not move stock. The three jobs the platform actually has are now
expressible.

**Nothing is separated yet**, as above. The seeded development operator still holds `OPERATOR` and
still does everything, which is correct for a development seed and would not be for a deployment.

**`isOperator` survives** for the paths where operator genuinely is the right answer, and
`hasAnyRole(token)` with no roles named returns false rather than vacuously true — the direction an
"any of these" check gets wrong when the list is empty. There is a test for that specifically.

**Still open.**

- **Granting the narrower roles to anything.** The mechanism exists and nothing uses it; the seed
  still grants `OPERATOR`. Issuing a `WAREHOUSE` account to the chaos harness would be the first real
  exercise of the split, and it would prove the boundary the way only a caller that lacks a privilege
  can.
- Whether a customer may read their own access log, and the operator-scoped view
  ([ADR 0034](0034-who-read-my-data.md)).
- Cancelled paid orders do not return their units to stock
  ([ADR 0030](0030-cancelling-a-paid-order.md)); an abandoned refund has no button
  ([ADR 0031](0031-when-nobody-answers.md)); the publisher still stores a hard-coded sampled flag;
  catalog has no `_info` endpoint.

*The split was since exercised by [ADR 0039](0039-nothing-ever-ran-without-operator.md), which seeded an account per role and moved CI and the chaos harness onto them. Until then every check minted its own token and every harness held OPERATOR, so a rule scoped to the wrong role would have been masked by the superset.*
