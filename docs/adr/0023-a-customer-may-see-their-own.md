# 0023 — A customer may see their own

**Status:** Accepted · **Date:** 2026-09-11 · **Phase:** post-roadmap

## Context

[ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md) closed the operational APIs behind an
`OPERATOR` role, and named the cost of doing it that way:

> A shopper still cannot read their own payment or shipment. Those endpoints now require an operator,
> which is safe but wrong in the long run — a customer should be able to track their own parcel.

That is the gap this record closes. The previous state was defensible as a stopping point and
indefensible as a destination: the person with the strongest claim to know where a parcel is — the
one waiting for it — was the only person who could not ask.

Worth being precise about what was wrong. Nothing was *exposed*. The failure was in the other
direction, and that direction has its own cost: an authorisation model that is merely restrictive
teaches people to work around it, and the workaround is usually an operator credential shared with
somebody who should not have had one.

## Decision

**Three categories of access, not two.** `OperatorFilter` previously knew *public* and *operator*.
It now knows a third: **signed in, with the handler deciding whose row it is.**

| | |
|---|---|
| public | no token — stock availability, `_info`, actuator |
| signed in | any valid token, **and the handler checks ownership** — a customer's own payment or shipment |
| operator | everything else, still the default |

The middle category is the dangerous one and is documented as such in the filter, because it is the
only one where passing the filter is not the whole check. A path listed there without an ownership
check in its handler is wide open, and looks exactly like a correct configuration from the filter's
side. That is why the tests for this change are mostly about what a *second* customer cannot see,
rather than what the first one can.

**Ownership is decided locally, not by asking the order service.** A payment row already carries the
`customerId` that arrived on `RequestPayment`; a shipment row carries the one from `ShipOrder`. Both
trace back to a token the order service verified, not to anything a client asserted — the chain
`token subject → Order.customerId → command → row` is unbroken since ADR 0021. Asking the order
service on every read would be a synchronous call to re-derive a value already stored, and a new way
for payment to be unavailable whenever the order service is, on a path that has no business failing
for that reason.

**Somebody else's row is 404, not 403.** The same reasoning as ADR 0021's orders: a 403 confirms the
identifier exists, which turns the endpoint into an oracle for whoever is enumerating them. It
matters more here than anywhere else on the platform, because a tracking number is printed on a
label, read aloud to carriers and pasted into emails — the most widely handled identifier this
system issues.

**But naming somebody else outright is 403.** `GET /payments?customerId=someone-else` from a
non-operator is refused, not quietly narrowed to the caller's own rows. Nothing is revealed by
refusing plainly — the caller supplied the identifier, so we are not confirming it exists — and
silently returning your own data in answer to a request for somebody else's reads as success.
Whoever wrote that call believes it worked and finds out otherwise much later, somewhere less
convenient.

**The split in shipping is by verb, and falls out naturally.** Reading a parcel is the customer's
business; dispatching and delivering it are the warehouse's. Those remain `POST`s under the same
path prefix and so fall through to the operator default — a customer marking their own parcel
delivered would be a customer editing the warehouse's record of reality.

## Alternatives considered

**Keep `customerId` as a required parameter and just allow customers to pass their own.** Rejected
for the reason ADR 0021 removed it from the order request body: a parameter naming whose data to
return is a parameter somebody will change to somebody else's. Deriving the subject from the token
and treating an explicit `customerId` as an operator-only override keeps exactly one way to say "my
own", and it is not a way anybody can mistype into somebody else's.

**Ask the order service who owns the order.** The properly normalised answer, and the wrong one
here. It puts a synchronous dependency on a read path, invents a failure mode for payment reads
whenever the order service is down, and re-derives a value that is already sitting in the row — a
value that is *more* trustworthy locally, because it was written once from a verified token rather
than fetched repeatedly from a service that might have been reconfigured since.

**A `CUSTOMER` role, mirroring `OPERATOR`.** Rejected because it would be a role every account has,
which is not a role — it is authentication with extra steps. The absence of `OPERATOR` already means
"an ordinary customer", and the ownership check is what actually does the work.

## Consequences

**Good.** The read paths now answer the question a customer actually has. `GET /payments` and
`GET /shipments` need no parameter at all, which is both simpler for a client and impossible to point
at somebody else.

**A side effect worth naming.** The shared exception handler grew a 403 mapping
for `OperatorRequiredException`, and the missing-parameter fix from the previous change now applies
to fewer endpoints than it did the day it was written — `customerId` is optional here now. The fix
is still correct and still tested; the endpoint that motivated it simply changed shape underneath it.
That is ordinary, and it is recorded here so the next reader does not conclude the handler was
written for nothing.

**Bad, and unchanged from ADR 0022.** The seeded operator's credentials are still in a migration in
this repository, and any deployment keeping that row still has no operator security at all. This
change makes that slightly more pointed: an operator can now read every customer's payment history,
which was already true but mattered less when no customer could read anything.

**Still open.** Per-service ports are still published, so each service continues to check for itself
rather than trusting the gateway — the reasoning in ADR 0022 stands unchanged. And an operator's
access to customer data is unaudited: there is no record of which operator read whose payments, which
is the kind of thing that is obvious to add and awkward to add later.
