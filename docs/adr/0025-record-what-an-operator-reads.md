# 0025 — Record what an operator reads

**Status:** Accepted · **Date:** 2026-09-13 · **Phase:** post-roadmap

## Context

[ADR 0023](0023-a-customer-may-see-their-own.md) opened the payment and shipment read paths to the
customers who own the data, and named what it left behind:

> An operator's access to customer data is unaudited: there is no record of which operator read whose
> payments, which is the kind of thing that is obvious to add and awkward to add later.

[ADR 0024](0024-the-development-operator-is-not-schema.md) repeated it. Two records naming the same
gap is the point at which it stops being a note and starts being a decision nobody has made.

The shape of the problem is specific. One role can read every customer's payment history and every
parcel on the platform, and that is deliberate — support cannot answer "where is my order" without
it. What was missing is any record that it happened. An access nobody can reconstruct is
indistinguishable, afterwards, from an access that never occurred, which cuts both ways: it offers no
protection to a customer and no defence to an operator.

## Decision

**A table per service, written on the read, recording only an operator reading somebody else's row.**

| | |
|---|---|
| `operator_id` | the token subject, not the email — emails change and this record has to stay true |
| `action` | `READ_PAYMENT`, `READ_PAYMENT_LIST`, `READ_SHIPMENT`, `READ_SHIPMENT_LIST` |
| `resource_id` | the id, order number or tracking number; null for a listing, which has no single subject row |
| `customer_id` | whose data it was |
| `correlation_id` | ties the row to the logs and the trace for that request |
| `read_at` | when |

Two indexes, because the two questions are asked from opposite ends: *who has looked at this
customer's data* during a complaint, and *what did this operator look at* during a review. One index
would serve one of them and table-scan for the other.

**It does not copy the data.** An audit table holding payment amounts is a second place for the same
customer information to leak from, and it answers no question the first one cannot.

**Only an operator reading somebody else's.** A customer reading their own is the ordinary path, and
recording it would bury the handful of rows that matter in the traffic nobody needs to review. An
operator reading their *own* payment is also just a customer reading their own. A **refused** read
records nothing either: nothing was disclosed, so there is nothing to account for.

Dispatching and delivering are not recorded here. This table answers "who read whose data", not "what
did the warehouse do" — conflating them would make it useless for both.

### Why a failure to record fails the read

The usual objection to synchronous auditing is that it lets a logging concern break a working
feature. **That objection does not apply here**, because the insert goes to the same database the
read has just come out of. There is no new dependency to fail: if the audit row cannot be written,
the query that produced the data could not have run either.

So the exception propagates, and the resulting property is the one worth having — an operator never
sees another customer's data without a record of it existing. Swallowing it would turn the single
failure that matters, *the audit table is gone*, into the state nobody notices.

The row is written **before** the data is read, not after, so an exception on the way out cannot
leave data fetched and the access unrecorded.

The real exception is a read-only replica, where selects succeed and inserts do not. This platform
has none. A deployment that adds one has to decide whether unrecorded reads or refused reads are
worse, and that is a decision rather than an oversight.

## Alternatives considered

**A central audit service, or a shared audit database.** The conventional answer, and it would put
every operator read behind a second system being available — which is how audit logging earns its
reputation for breaking the thing it was watching. It would also contradict
[ADR 0003](0003-database-per-service.md) for no gain: nothing here needs to join across services.

**An event on the bus, consumed by an auditor.** Tempting, because the platform already has an outbox
and this looks like an event. Rejected because the outbox's guarantee is *eventually*, and an audit
record that is eventually written is one that is missing precisely when the service crashes mid-read
— the case it exists for. Worse, the outbox writes in the caller's transaction, and these are reads;
there is no transaction to join.

**Log lines only, scraped into whatever collects them.** Cheapest, and it is half of what was
implemented — the access is logged as well as stored. Rejected as the whole answer because "who has
read this customer's data" then depends on a retention policy set elsewhere for unrelated reasons,
and answering it means a text search rather than a query.

**Audit every read, including a customer's own.** Simpler to reason about, with no condition to get
wrong. Rejected on volume and signal: customer reads are the normal path, so the table would be
dominated by rows nobody will ever look at, and the reviewable events would be buried in them.

## Consequences

**Good.** "Who looked at my payment history" is now a query rather than a shrug. Every operator read
of somebody else's data is recorded, and the record ties to the logs and the trace through the
correlation id.

**A 500 is now a correct answer to an operator read.** If the table is missing the service refuses
rather than quietly serving an unrecorded read, and there is a test that removes the table to prove
it. That is a deliberate new failure mode, and it is bounded: it affects operator reads of other
people's data, not customers reading their own.

**The order service is asymmetric, and it is worth knowing.** An operator cannot read somebody else's
order at all — `OrderController` has no operator bypass, so there is nothing to audit there. That is
not obviously the right end state, since support answering "where is my order" may well need it, but
it is the existing behaviour and opening it is a separate decision with its own record.

**Nothing expires these rows.** The outbox has a retention sweeper; this table has none, so it grows
with operator activity forever. That is the correct default for an audit trail and the wrong one for
a disk, and a retention policy is a decision about how long the platform wants to be able to answer
the question — which is a real decision and not one to make silently here.

**Still open.** Per-service ports remain published, so each service keeps checking for itself rather
than trusting the gateway; ADR 0022's reasoning is unchanged.
