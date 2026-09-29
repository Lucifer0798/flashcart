# 0036 — Anyone could set the price

**Status:** Accepted · **Date:** 2026-09-28 · **Phase:** post-roadmap · **Closes a gap left by [ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md)**

## Context

The catalog service had **no authentication of any kind**, and eleven endpoints that write: create,
update and delete a product; create, update and delete a category; create a flash sale, add items to
it, remove items, schedule it, cancel it.

[ADR 0011](0011-order-owns-no-prices.md) made the order service ask catalog for each SKU's
`effectivePrice` and copy it onto the order line, so that a checkout cannot be discounted by a client
supplying its own price:

> **There is no price field in the request, deliberately.** A checkout that trusts a client-supplied
> price is one anyone can discount to zero.

That protection assumed the catalogue could not be edited by the person doing the buying. It could.
Demonstrated end to end against a running stack, with no token at any step:

```
POST /api/v1/products      -> 201    created at 999.00
PUT  /api/v1/products/{id} -> 200    repriced to 0.01
GET  /api/v1/products/sku/…         effectivePrice: 0.01
POST /api/v1/orders                 total: 0.01 USD, unitPrice 0.01
```

The same surface also allowed inventing a flash sale, setting its allocations, and scheduling it.

[ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md) closed exactly this class of hole and
argued it in terms that apply here word for word:

> Until now inventory, payment and shipping had no authentication of any kind. Nothing reached them
> from outside *in this deployment* — but that was a claim about a compose file rather than a property
> of the system, and compose publishes every one of them on its own host port.

Catalog was not in that change. It is the service that sets prices.

### Why it was missed, and why that is the recurring shape

ADR 0022 went after services with *operational* APIs — receive stock, mark a parcel delivered — and
catalog reads as a content service. But a price is not content; it is an input to what a customer is
charged, and the one service the order service trusts.

This is the fourth omission of the same kind. [ADR 0028](0028-looking-is-not-acting.md) found an
operator unable to read an order because the check landed before the role existed.
[ADR 0035](0035-nothing-behind-the-check.md) found the order service without a default-deny filter
because it already authenticated somewhere. Both were accidents of build order that nobody had
written down, and so was this.

## Decision

**Reads stay public. Every write needs an operator.**

The same `OperatorFilter` the other four services use, with a public list naming every `GET` and
nothing else. The middle, signed-in category is **deliberately empty**: a shopper has no business
editing a catalogue, so there is no path here where passing the filter is only half the check.

Every public rule is method-qualified. `GET /api/v1/products/*` opens reading one product and says
nothing about `PUT` to the same path, which is how one rule opens a read without opening the write
beside it.

`/actuator/**` is public because compose health-checks it, and a container that cannot answer
restarts forever.

### Two things this needed that were not obvious

**A JWT library.** `nimbus-jose-jwt` is `optional` in `flashcart-common` on purpose, so a service
that authenticates nothing does not carry one. Catalog was such a service. Adding the filter without
adding the dependency starts the context and then fails it with
`NoClassDefFoundError: com/nimbusds/jose/JOSEException` — which is a startup failure, not a test
failure, and would have reached CI as a crash-looping container.

**Four harness call sites.** CI creates products twice, the chaos harness seeds one per scenario, and
the e2e wrong-verb probe lived on a catalog path. All four were unauthenticated because they could
be. The chaos harness already held an operator token and simply was not sending it on that one call.

### The wrong-verb probe, for the third time

CI asserts that a wrong verb yields 405 from the shared error handler rather than a 500. That probe
has now moved twice — off `/api/v1/orders` when [ADR 0035](0035-nothing-behind-the-check.md) made
that service default-deny, and off catalog here — because in a default-deny service the filter
answers 403 before Spring can decide the verb is wrong.

Both times the cheap fix was to accept 403 in the assertion, and both times that would have kept CI
green while the check stopped proving anything: it would pass with the shared handler entirely
broken. It now runs **as an operator**, on a verb no controller maps. That passes the filter, so
whatever answers is the error handler rather than the guard in front of it — and it no longer depends
on some service happening to lack a filter.

## Alternatives considered

**Put catalog writes behind the gateway only.** The gateway already has a public-path list, and it
works the other way round: it names what is open and lets the rest through to be checked later, which
is right for an edge that mostly routes and wrong as the only check. Per-service ports are published
([ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md)), so :18081 is reachable directly.

**A separate admin role rather than reusing `OPERATOR`.** Arguably more correct — receiving stock and
repricing a catalogue are different jobs — and rejected as a bigger decision than this. Introducing a
second role means deciding how roles compose, and doing that inside a change that closes a live hole
is how the hole stays open longer. Worth its own record.

**Leave reads requiring nothing and writes requiring a signed-in user.** Would have stopped the
anonymous case and not the interesting one: a shopper repricing the product they are about to buy is
precisely the attack. Being signed in is not running a shop.

## Consequences

**Good.** The price a customer is charged can no longer be chosen by the customer. ADR 0011's
guarantee is now true rather than conditional on nobody editing the catalogue.

**Every service that holds customer-affecting state is default-deny**: inventory, payment, shipping
(ADR 0022), order (ADR 0035) and catalog. The user service is the remaining one, and it is the
sign-in surface — register and sessions must be public, and `/me` already checks its own token. That
is an argument worth making explicitly rather than assuming, and it is the last one left.

**Four harnesses now need an operator token to seed a catalogue.** That is the cost of the change and
it is the right cost: a harness that could seed anonymously was exercising a hole.

**Still open.**

- **The user service has no filter.** Probably correct, definitely unargued — the same sentence that
  preceded each of the last four omissions.
- Catalog has no `_info` endpoint, alone among the services. Harmless; noticed while listing its
  surface.
- Whether repricing deserves its own role rather than `OPERATOR`, as above.
- Whether a customer may read their own access log, and the operator-scoped view
  ([ADR 0034](0034-who-read-my-data.md)).
- Cancelled paid orders do not return their units to stock
  ([ADR 0030](0030-cancelling-a-paid-order.md)); an abandoned refund has no button
  ([ADR 0031](0031-when-nobody-answers.md)); the publisher still stores a hard-coded sampled flag.

*The user service was since argued through in [ADR 0037](0037-the-last-one-unargued.md) and given the same filter. Nothing was open there, but an anonymous write to `/me` answered 400 rather than 401, which the filter fixes by refusing before the body is bound.*
