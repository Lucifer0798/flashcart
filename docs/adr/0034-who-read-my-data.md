# 0034 — Who read my data

**Status:** Accepted · **Date:** 2026-09-25 · **Phase:** post-roadmap · **Completes work deferred since [ADR 0025](0025-record-what-an-operator-reads.md)**

## Context

[ADR 0025](0025-record-what-an-operator-reads.md) created `operator_access_log` and said plainly that
nobody queries it on an ordinary day. Every record since repeated that it had no reader:

| ADR | |
|---|---|
| [0026](0026-deleting-an-audit-record-is-a-decision.md) | "the audit table has no reader — answering 'who read my data' means a SQL query, not an endpoint" |
| [0027](0027-the-outbox-hop-is-a-span.md) | "the audit table added in ADR 0025 has no reader" |
| [0028](0028-looking-is-not-acting.md) | "…and it now exists in three services rather than two" |
| [0029](0029-an-operator-read-was-a-silence.md) | "The audit table has no reader, and now lives in three databases" |
| [0030](0030-cancelling-a-paid-order.md) – [0033](0033-shipped-did-not-mean-shipped.md) | the same, four more times |

Eight consecutive records naming one gap is not a deferral any more. It is also the only such item in
the set: per-service ports appear in fifteen ADRs but every mention restates ADR 0022's *decision*,
which is a different thing from an unbuilt one.

## Decision

**Each service exposes a reader for its own log, and reading it is itself recorded.**

```
GET /api/v1/order/_access-log?customerId=…
GET /api/v1/payment/_access-log?customerId=…
GET /api/v1/shipping/_access-log?customerId=…
```

### Three endpoints, because there are three tables

The log is written on the same connection as the read it records, which is precisely what lets a
failure to record fail the read ([ADR 0025](0025-record-what-an-operator-reads.md)). That property
depends on the table being local, so there are three of them
([ADR 0003](0003-database-per-service.md)). An aggregating endpoint would have to call two other
services synchronously to answer, and [ADR 0009](0009-no-transaction-across-a-network-call.md)'s
reasoning about not doing that is why this platform has one synchronous hop left, not four.

So "who read my data" is three requests. That cost was named when the third table appeared and it is
unchanged; what changes is that each third is now answerable without a database client.

### The singular path, which is not cosmetic

These sit on `/api/v1/order`, `/api/v1/payment` and `/api/v1/shipping` — beside `_info` — rather than
under the plural resource prefixes. The gateway already routes both.

The reason is a rule written for something else. Payment and shipping declare
`GET /api/v1/payments/*` and `GET /api/v1/shipments/*` as signed-in paths, and `OperatorFilter`'s
single star matches **exactly one more segment**. `/api/v1/payments/_access-log` is one more segment.
The audit log would have been readable by every signed-in customer, granted by a rule intended for
`GET /api/v1/payments/{id}`, with nothing in the diff to suggest it.

### Recorded before it answers

An operator reading this log is an operator reading information about a customer, so it is recorded
as `READ_ACCESS_LOG` like any other access — and recorded *before* the query runs, keeping ADR 0025's
invariant that the row exists before the data is disclosed.

That makes a log read appear in its own response, which is worth stating rather than discovering: **the
newest entry in any response is the request that produced it.** The alternative — record afterwards, so
the response looks tidier — would put a disclosure before its record for the one table where that
matters most.

### Operators only, and not via `CustomerDataAccess`

The obvious wiring is `CustomerDataAccess.mayRead`, which already grants an owner or an operator and
records only the operator. It is deliberately not used here, because it would grant **the customer**
named in the query.

Whether a shopper should be able to see which staff opened their order is a real question with a real
answer either way, and it is not one to settle as a side effect of building a reader. It stays open.

Refusal is **403 `OPERATOR_REQUIRED`**, not 404. The oracle argument that makes somebody else's order
a 404 ([ADR 0021](0021-the-client-does-not-say-who-it-is.md)) does not apply: the path is fixed and
published in the OpenAPI document, and the customer id in the query is one the caller already had.

## What this exposed: the order service is not default-deny

Payment and shipping have an `OperatorFilter` and are default-deny, so this endpoint is closed there
by the arrangement rather than by anything written for it. **The order service has no such filter.**
Every one of its endpoints authenticates for itself, so the check inside `OperatorAccessLogReader` is
the only thing refusing a customer, and if it were removed nothing behind it would notice.

That asymmetry is not a decision anybody recorded. [ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md)
added the filter to the two services that had *no* authentication at all; the order service already
had some, so it was passed over. It is an accident of build order, the same shape as the one
[ADR 0028](0028-looking-is-not-acting.md) found in operator order access.

This was measured, not inferred. With the operator check in `OperatorAccessLogReader` disabled:

| | |
|---|---|
| payment's audit suite | **14 pass** — `OperatorFilter` refuses the customer before the code runs |
| order's `customerCannotReadTheAccessLog` | **fails** — nothing else refuses |

Not fixed here, because adding a default-deny filter to a service with eight existing endpoints is a
security-posture change that deserves its own argument and its own test pass — not a paragraph in a
change about reading a table. It is named, and a test asserts the guard it currently depends on.

## Alternatives considered

**An aggregating endpoint on the gateway.** The shape a reader wants: one call, one answer. Rejected
for now because the gateway is routing and correlation ids, and making it a read-model owner is a
larger decision than this. It is the obvious later move.

**Expose the operator-scoped view too** — "what did this operator look at", which
`idx_operator_access_operator` was created for. Left out because recording it is not solved: the row
shape has one customer subject, and a review of everything one operator touched discloses many
customers at once. Recording it as a single row would be a lie about scope, and one row per customer
in the result is an explosion. The index stays unused for now, which is honest and visible.

**Let the customer read their own.** See above — a genuine question, deliberately not answered here.

**Don't record reads of the log.** Tempting, because it makes the table grow as it is reviewed.
Rejected: a log whose whole purpose is accountability should say who has been looking at it, and one
extra row per review is a small price for that.

## Consequences

**Good.** The question ADR 0025 created the table to answer can now be asked over HTTP, by the people
entitled to ask it, and asking is on the record.

**A read of the log adds a row to the log.** Bounded at one per request, and self-documenting.

**`idx_operator_access_operator` is still unused**, because the operator-scoped view is not built. An
unused index is harmless, but it is the same family of thing this project keeps finding, so it is
named rather than left to be rediscovered.

**Still open.**

- **Whether a customer may read their own access log.** The privacy question above.
- **The operator-scoped view**, and how to record it.
- **The order service is not default-deny**, as above — now the largest unrecorded asymmetry left.
- An aggregating reader, so the answer is one call rather than three.
- Cancelled paid orders do not return their units to stock ([ADR 0030](0030-cancelling-a-paid-order.md));
  an abandoned refund has no button ([ADR 0031](0031-when-nobody-answers.md)); the publisher still
  stores a hard-coded sampled flag. Per-service ports remain published, which is
  [ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md)'s decision rather than an omission.
