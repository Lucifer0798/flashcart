# 0022 — Being signed in is not being a warehouse

**Status:** Accepted · **Date:** 2026-09-09 · **Phase:** post-roadmap

## Context

[ADR 0021](0021-the-client-does-not-say-who-it-is.md) closed the hole where a client asserted its own
identity, and named what it did not close:

> The inventory, payment and shipping APIs are still open. Nothing reaches them from the internet in
> this deployment, but "nothing reaches them" is a claim about a compose file, not a property of the
> system, and per-service ports are published.

That is the gap this record closes. Until now, anybody who could reach the platform could receive five
thousand units of stock, adjust a movement ledger, create an allocation, read every payment in the
system, or mark a parcel delivered — **without an account at all**.

A shopper's token is not sufficient either. Being signed in makes somebody a customer. It should not
make them a warehouse.

## Decision

**One role, `OPERATOR`, and default-deny on the three operational services.**

The token gains a `roles` claim. The user service seeds exactly one operator account; there is no
endpoint that grants a role, because becoming an operator should be an `UPDATE` somebody runs
deliberately — the right amount of friction for the difference between reading your own orders and
adjusting a stock ledger.

`OperatorFilter` in `flashcart-common` protects **everything a service does not explicitly declare
public**. The direction matters more than the mechanism:

- a service that lists what to *protect* forgets a new endpoint and ships it **open**;
- a service that lists what to *expose* forgets a new endpoint and ships it **closed**.

Both mistakes are equally likely and only one is survivable. This is also why it is one filter in
`common` rather than three copies: three implementations is three chances to get the default subtly
backwards, and the wrong one would look exactly like the right one until somebody probed it.

**What stays public, and why:**

| | |
|---|---|
| `GET /api/v1/inventory/stock/{sku}` | A shopper needs to know how many are left, and a count of a public product is nobody's personal data |
| `_info` and `/actuator/**` | How the stack is inspected; already exposed by design |

**And exactly that far.** The public rule is `GET /api/v1/inventory/stock/*` — one more path segment, no
more — because `/{sku}/movements`, `/{sku}/receive` and `/{sku}/adjust` sit directly underneath it and
the list of every SKU sits directly above. A `**` there would have published the movement ledger while
every check still passed, since the check everybody writes is "can an anonymous caller read
availability?" and the answer would have stayed yes. The verb matters for the same reason: `POST` to
that same path creates stock.

This is not hypothetical. The first cut of the matcher understood only `**` and exact paths, so the
single-star rule matched nothing and availability answered 401 to everybody — caught by the
integration tests. The obvious repair was to widen the rule to `**`, which would have fixed the
symptom, passed CI, and opened the ledger. Both mistakes are one character wide, which is why the
matcher now has unit tests of its own rather than a comment asking the next person to be careful.

**401 versus 403 here, and why it differs from the order service.** A valid token without the role
gets **403**; no usable token gets **401**. That is the opposite of ADR 0021's deliberate 404 for
somebody else's order — there, distinguishing "not yours" from "does not exist" hands an attacker an
oracle over guessable order numbers. Here the paths are fixed and published in the OpenAPI document,
so there is nothing to conceal, and an operator debugging a permissions problem deserves to be told
which of the two things is wrong.

**Each service enforces this itself.** The gateway stops carrying unauthenticated traffic to them,
which is worth doing on a platform built around shedding doomed work early — but compose publishes
every service on its own host port, so the gateway remains an optimisation rather than a boundary.
Same reasoning as ADR 0021, applied to three more services.

## Alternatives considered

**A network boundary instead: stop publishing the per-service ports.** Genuinely the better answer,
and it would make the gateway a real boundary rather than a fast path. Rejected for now because the
README documents those ports — catalog's Swagger UI on 18081, the Grafana and Zipkin links — and
because a security property that depends on nobody adding a port back is weaker than a check in the
code. The two are complementary; this is the half that survives a compose file being edited.

**A static service token in an environment variable.** Simpler than roles, and it would have avoided
the seeded account. Rejected because it is a second authentication mechanism sitting beside the first,
and two mechanisms means two things to rotate, two things to get wrong, and an inevitable argument
about which one a new endpoint should use.

**Per-endpoint scopes: `stock:write`, `shipment:dispatch`, and so on.** Correct at a scale this
platform is nowhere near. Inventing a permission matrix before anything needs one produces a matrix
nobody maintains and checks nobody reads. One role is honest about how much authorisation this system
actually has.

## Consequences

**Good.** The operations that move real quantities now require an account that was deliberately given
that power. The blast radius was, again, the evidence: `PaymentIT`, the whole `AbstractInventoryIT`
hierarchy, `InventoryKafkaIT`, `ShippingIT`, both harnesses and four CI steps all had to learn to sign
in. Every one of them had been exercising an endpoint that anybody could have called.

**Bad, and stated plainly.** The seeded operator's credentials are in a migration in a public
repository. That is a development convenience so `docker compose up` produces a stack whose harnesses
work, and **any deployment that keeps that row has no operator security at all**. The migration says
so in the file that creates it.

A shopper still cannot read their own payment or shipment. Those endpoints now require an operator,
which is safe but wrong in the long run — a customer should be able to track their own parcel. Doing
it properly needs ownership checks against the order service, which is the same work ADR 0021 did for
orders and is the obvious next piece rather than something to bolt on quickly.

Role changes only take effect on the next sign-in, since the role is a claim in a token that lives for
twelve hours. Revoking an operator means waiting out the token or rotating the signing secret, which
is the cost of stateless tokens and the reason the TTL is short.
