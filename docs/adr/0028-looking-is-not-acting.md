# 0028 — Looking is not acting

**Status:** Accepted · **Date:** 2026-09-19 · **Phase:** post-roadmap · **Reverses a position taken in [ADR 0025](0025-record-what-an-operator-reads.md)**

## Context

[ADR 0025](0025-record-what-an-operator-reads.md) noticed an asymmetry and left it alone:

> An operator cannot read somebody else's order at all — `OrderController` has no operator bypass, so
> there is nothing to audit there. That is not obviously the right end state, since support answering
> "where is my order" may well need it, but it is the existing behaviour and opening it is a separate
> decision with its own record.

[ADR 0026](0026-deleting-an-audit-record-is-a-decision.md) and
[ADR 0027](0027-the-outbox-hop-is-a-span.md) both repeated it. This is that record.

**The asymmetry was an accident of build order, not a boundary.** Ownership landed on orders in the
change that built the user service, before the `OPERATOR` role existed at all. Payments and shipments
were opened to operators afterwards. Nobody ever decided orders should be different; they were simply
done first.

## The argument: it protects almost nothing

An operator can already read any customer's payment and shipment. Between them those disclose:

| Already visible to an operator | Only in the order |
|---|---|
| `customerId`, `orderNumber`, `orderId` | order status and allowed next states |
| amount, currency, payment status, failure code and reason | `flashSaleId` |
| carrier, tracking number, dispatch and delivery times | the subtotal/total split |
| **`lines` — the SKUs and quantities shipped** | `reservationExpiresAt`, cancellation reason |

So the operator already knows who the customer is, what they bought, what they paid, and where it
is. Refusing them the order withholds a flash-sale id and a subtotal line, while leaving support
unable to answer "where is my order" from the order itself — which is the question the role exists
to answer.

A rule that costs real capability and protects almost nothing is not a security boundary. It is an
inconsistency that happens to point in the safe direction.

## Decision

**Reading somebody else's order is allowed for an operator, and recorded. Cancelling it is not.**

`OrderController` moves from `CallerIdentity` to `CustomerDataAccess` — the swap ADR 0023's split was
designed for. The reads use `mayRead`, so an operator's access to another customer's row is written
to `operator_access_log` before the data is returned, exactly as in payment and shipping. A customer
reading their own records nothing, and a refused read records nothing, because nothing was disclosed.

`READ_ORDER` and `READ_ORDER_HISTORY` are separate actions. The history is the state machine's audit
trail — what support reads to answer "why is this cancelled" — and conflating the two would make the
log unable to distinguish someone glancing at an order from someone reading its whole life.

**Cancel keeps a customer-only check**, and that is the line this record draws. Reading discloses
facts an operator can already assemble. Cancelling releases stock and moves a state machine on a
customer's behalf: it discloses nothing and destroys something. Whether an operator should be able to
do that is a genuinely different question, and it is not answered here. Shipping already draws the
same line from the other side — reading a parcel is the customer's business, dispatching it is the
warehouse's.

**The listing regains a `customerId` parameter**, which [ADR 0021](0021-the-client-does-not-say-who-it-is.md)
had removed on the grounds that "a parameter naming whose data to return is a parameter somebody will
change to somebody else's". That reasoning holds for a parameter anybody may pass. It is answered the
way ADR 0023 answered it for payments and shipments: omitting it remains the only way a customer can
ask, so there is nothing for them to mistype into somebody else's, and supplying it requires the role
and is refused outright otherwise rather than quietly narrowed to the caller's own rows.

## What this cost, and why that is the design working

The order service had no `operator_access_log`. `CustomerDataAccess` cannot be constructed without
one, so granting this power *required* deciding to store the audit — which is precisely the
constraint [ADR 0023's](0023-a-customer-may-see-their-own.md) two-type split was built to impose. The
migration is `V4`, identical to payment's and shipping's.

Had the ownership check been a single type with a `mayRead` on it, this change would have been a
one-line swap of collaborator with no audit trail and nothing to notice.

## Alternatives considered

**Leave it closed.** Defensible on the principle that access should be minimal, and rejected because
the minimum here is incoherent: the same facts leak through two other services. A restriction that
can be trivially worked around teaches people to work around it, which is the argument ADR 0023 made
about customers and made again here about support.

**Open cancel as well, so support can act on a call.** Probably wanted eventually, and deliberately
not bundled. It is a write on a customer's behalf, it has a compensation path behind it, and it
deserves the argument on its own terms rather than arriving as a side effect of a change about
reading.

**A separate support-only API rather than the customer endpoints.** The conventional answer for a
back office, and disproportionate here: it duplicates four read paths to express a distinction the
role and the audit log already express.

## Consequences

**Good.** Support can answer the question the role exists for, and every time they do it is on the
record with who looked, whose order it was, and which of the two reads it was.

**A customer-facing endpoint now behaves differently by role**, which is a thing to keep in mind when
reading `OrderController`: the same path returns 404 for one signed-in caller and 200 for another.
That is already true of payments and shipments, so at least it is now uniformly true.

**Still open.** Per-service ports remain published, so each service keeps checking for itself rather
than trusting the gateway. The publisher still stores a hard-coded sampled flag. The audit table has
no reader — and it now exists in three services rather than two, which makes "who read my data" a
query against three databases rather than one. That is the cost of database-per-service and was
always going to be the shape of it, but it is worth naming now that it is three.

*The audit reader was since built by [ADR 0034](0034-who-read-my-data.md): each service exposes its own, since each has its own table.*
