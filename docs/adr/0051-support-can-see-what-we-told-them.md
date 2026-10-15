# 0051 — Support can see what we told them

**Status:** Accepted · **Date:** 2026-10-15 · **Phase:** post-roadmap · **Builds on [ADR 0025](0025-record-what-an-operator-reads.md), [ADR 0038](0038-one-role-did-too-much.md) and [ADR 0048](0048-the-order-tells-its-own-story.md)**

## Context

Since [ADR 0048](0048-the-order-tells-its-own-story.md) and [ADR 0050](0050-the-emails-that-were-not-sent.md)
the platform emails a customer at every turn of an order and when their money comes back. Every one of
those emails is a row in the user service's `notices` table. The row says whether the email was sent,
when, how many times the mail server refused it and what it last said, or that it was skipped and why.

The first thing a customer asks support about any of this is *"did you ever email me?"*. The answer was
in that table, and nothing but SQL could read it. Support could not tell "we sent it on Tuesday, check
your spam" from "our mail server has refused it eleven times" from "it was skipped: it was a day old
before we could send it".

## Decision

### `GET /api/v1/user/_notices`, for support

It lists one customer's emails, newest first. Each entry has:

- the subject and text, rendered from the stored row by the same code the sender uses;
- the status: `SENT` with the time, `PENDING` with the next attempt, or `SKIPPED` with the reason
  (`STALE`, `NO_RECIPIENT`);
- the attempts and the mail server's last refusal. The refusal is kept after a later attempt succeeds,
  because it is why that email went out late.

The customer is named by **email address**, which is what they will give support, or by id. Exactly one
is required. An address with no account is a 404. `orderNumber` narrows the list to one order.

It reads the queue itself rather than a copy, so the answer cannot disagree with what the sender did.
The text is rendered at read time, not stored at send time. It is the same words unless the wording has
changed since, and the field says so.

### Support only, and not the customer, even for their own

The endpoint is for the `SUPPORT` capability ([ADR 0038](0038-one-role-did-too-much.md)); `OPERATOR`
carries it as a superset. It is checked twice, as every staff read is
([ADR 0021](0021-the-client-does-not-say-who-it-is.md)): by the filter's role rule, and again in the
handler.

A shopper may not read it, **not even for themselves**. What it shows is operational: retry counts, the
mail server's own error text, internal skip reasons. A customer's view of their own emails is their inbox.

### The read is recorded, so the user service keeps an access log

Reading what a customer was told is a staff read of a customer's data, so it is recorded as
[ADR 0025](0025-record-what-an-operator-reads.md) requires. The record is written before the data is
returned, on the same connection, and a failure to record fails the read. That needs an
`operator_access_log` in the user service's database, which until now had no staff reads to record. It
has one now, the same table as the other three services. It gets:

- the reader `GET /api/v1/user/_access-log`, as in the others;
- the retention job, off unless configured ([ADR 0026](0026-deleting-an-audit-record-is-a-decision.md)).
  Compose sets it to `P365D`, as it does for the others.

A read narrowed to one order records the order number. A refused read records nothing, because nothing
was read.

## Verified

- `NoticeIT`:
  - support sees sent, pending (with the mail server's error and the next attempt) and skipped (with
    the reason), newest first, found by an email address in a different case;
  - by id, narrowed to one order, never another customer's;
  - neither or both identifiers is a 400, and an unknown address is a 404;
  - the read is recorded with the order number, and `_access-log` returns it;
  - anonymous is 401; a warehouse token, and the customer themselves, are 403; a refused read leaves no
    record;
  - the handler refuses a customer on its own, called directly, since over HTTP the filter answers first.
- Mutations: 9 of 10 caught. The 9 were: the read not recorded; recorded without the order; every
  customer's emails returned; the order filter ignored; oldest first; a next attempt shown for a sent
  email; the address not normalised; the handler's role check removed; both checks opened to anyone
  signed in. The survivor is the filter alone opened to the signed-in, which the handler still refuses.
  That is the point of checking twice. The handler's check survived too until the direct test above was
  added, because over HTTP the filter always refused first.
- Live, on the compose stack: with Mailpit stopped and an order cancelled, support saw the cancellation
  `PENDING` after one attempt, with the connection error. The refund email for the same order waited
  behind it at 0 attempts, as ADR 0048's per-order ordering requires. With Mailpit back, support saw
  both `SENT`, the cancellation after 2 attempts. The shopper and a warehouse token got 403, and
  `_access-log` showed every read.
- CI: the outside-refund step now also checks that support sees that order's three emails as `SENT`, and
  that the shopper is refused.

## Consequences

**Good.** The most common question about an email now has an answer that does not need a database
client, and the answer is the record the sender itself acted on.

**Accepted.** "Who read my data" is now four requests, one per service, because there are four logs.
[ADR 0028](0028-looking-is-not-acting.md) named that cost when the third appeared, and it grows by one.

**Still open.**

- Support can see that an email is stuck, but cannot send it again. A resend is an action with its own
  questions, such as whether a stale email should ever go out late, and it is not answered here.
- `compose.yaml` does not pass `TRACE_SAMPLING` to the services ([ADR 0049](0049-loose-ends.md)).
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
