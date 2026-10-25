# 0056 — A customer sees that staff looked

**Status:** Accepted · **Date:** 2026-10-25 · **Phase:** post-roadmap · **Answers the question [ADR 0034](0034-who-read-my-data.md) left open**

## Context

Since [ADR 0025](0025-record-what-an-operator-reads.md), every staff read of a customer's data is recorded
in an `operator_access_log`, one per service. Four services keep one now: order, payment, shipping and
user. [ADR 0034](0034-who-read-my-data.md) gave support a reader for each, and deliberately refused the
customer. It named whether a shopper may see which staff opened their order as a privacy question with a
real answer either way, not one to settle as a side effect of building a reader. It also said that
opening the reader to customers was a security-posture change that deserved its own argument and its own
tests. This is that argument.

## Decision

### The customer learns that staff looked, what at, and when. Not who.

Each entry a customer sees has four fields:

- the action;
- a sentence saying it in words, such as "A member of staff viewed one of your payments" or "A member of
  staff viewed the emails we sent you";
- the resource, when it was one thing;
- the time.

It does not include the staff member's id. Nor does it include the correlation id, which could be traced
through the logs to the same person.

That answers what a customer actually wants to know about their data: whether anyone looked, when, and
at what. It does not hand them a person to aim a complaint at. Support still sees who, from its own
reader, and a complaint about an access can be taken up there. An opaque staff reference was
considered. It names nobody, but it would let a customer tell whether the same person keeps looking,
which is a partial "who" by another route.

Staff reading the access log itself is listed too, as "viewed this record of who has looked at your
data". Leaving it out would make the record look tidier than what happened.

### One read per service, scoped by the token

`GET /api/v1/{order,payment,shipping,user}/_access-log/mine`, each on its service's singular prefix,
beside the support reader. The access log lives in four databases, and the platform does not fan out
synchronously between services, for the reason ADR 0034 gave. So a complete answer is four calls. Each
service answers for its own data, and one service being down does not stop the other three answering.

Whose entries come back is decided by the token's subject, and nothing in the request can change it. A
`customerId` parameter is ignored, not obeyed ([ADR 0023](0023-a-customer-may-see-their-own.md)). The
filter lists each path as signed-in by exact match, so the support reader one segment up stays
support-only, and its existing refusal of a customer is unchanged.

A customer reading their own log is not recorded. It is the ordinary path of reading one's own data,
which ADR 0025 does not audit.

## Verified

- Payment (`OperatorAccessLogIT`), the real flow: support reads two customers' payments. One customer then
  sees one entry, with exactly the four fields and the payment's order number. Their own read adds no row,
  a `customerId` naming the other customer changes nothing, and a token that does not verify gets 401.
- Order (`OrderIT`), shipping (`OperatorAccessLogIT`) and user (`NoticeIT`) each check that the
  customer sees only their own entries, worded for the action, with no other fields, and that their read
  is not recorded. In user, the entry comes from a real support read of the customer's emails.
- Mutations, all 7 caught: every customer's entries returned; the customer's own read recorded; the
  descriptions not given; and, one per service, the route not opened in the filter.
- CI: in the outside-refund step, after support reads the shopper's emails, the shopper's own read shows
  `READ_NOTICES` and no `operatorId`.

## Consequences

**Good.** The record ADR 0025 began keeping is now something the person it is about can see, not only
the staff it is about.

**Accepted.** Four calls for the whole picture. A customer told only "a member of staff" cannot tell one
person looking ten times from ten people looking once.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
