# 0066 — Your data, as a file

**Status:** Accepted · **Date:** 2026-11-06 · **Phase:** post-roadmap

## Context

A customer can read most of what the platform holds about them, but only one piece at a time and only if
they know where to look: `/users/me`, `/orders`, each order's history, payments, parcels, the waitlist, and
since [ADR 0056](0056-a-customer-sees-that-staff-looked.md) when staff looked. Some of it they could not read
at all. The emails we kept are visible to support ([ADR 0051](0051-support-can-see-what-we-told-them.md))
but not to the person they were sent to. Sessions, password resets and sale caps have no customer-facing read.

[Closing an account](0053-closing-an-account.md) also erases the identity for good, and until now there was
no way to take a copy first.

## Decision

### One file per service, whose is decided by the token

`GET /api/v1/{user,order,payment,shipping,inventory}/_export/mine`, each on its service's singular prefix.
Every one answers in the same shape, `DataExport`: `service`, `customerId`, `exportedAt`, then its sections,
with `Content-Disposition: attachment` so a browser saves it as `flashcart-<service>-export.json`.

A complete copy is five calls, for the reason [ADR 0034](0034-who-read-my-data.md) and ADR 0056 gave: each
service holds its own data and the platform does not fan out synchronously between them. A gathering endpoint
would have to hold a customer's token, call five services, and decide what to do when one is down. As it
stands one being down costs only that one file, and the file says itself where it came from. The catalog and
the gateway are not in the list because they hold nothing about a customer.

Whose data comes back is the token's subject, and nothing in the request can change it. A `customerId`
parameter is ignored, not obeyed ([ADR 0023](0023-a-customer-may-see-their-own.md)). Each path is listed in its
filter as signed-in by exact match.

| Service | Sections |
|---------|----------|
| user | `profile` (with addresses), `emails`, `sessions`, `passwordResets`, `roleChanges`, `staffAccess` |
| order | `orders` (each with its full `history`), `staffAccess` |
| payment | `payments`, `staffAccess` |
| shipping | `shipments`, `staffAccess` |
| inventory | `waitlist`, `reservations`, `saleLimits` |

### What is left out is decided, not forgotten

- **No credentials.** No password hash, no refresh-token hash, no reset-token hash. They are not information
  about the customer in any useful sense, and a copy of one in a downloads folder only makes an offline guess
  possible. Sessions are listed by when each token was issued and what became of it, and resets by when they
  were asked for and whether they were used.
- **No working reset link.** A reset email appears in the file, but its body is rendered from the queue, where
  the link has never been: the sender mints it at send time and keeps only its hash
  ([ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md)). The test takes the token out of the email
  that actually went out and looks for it in the raw file, because this is the one omission that would turn a
  stolen file into a stolen account.
- **No mail-server detail.** No `lastError`, retry count, next attempt or queue key. Those describe our mail
  rather than the customer, and a refusal can name internal hosts. Support's view keeps them. Subject, body,
  order number, status and the two times stay, which is what the customer would want to know.
- **Not who, again.** `staffAccess` is the customer's view from ADR 0056, with no staff id. A role change says
  which role, granted or revoked, and when. It leaves out `granted_by`, and also `reason`, which staff write for
  staff and which can name people. An account that was never staff has an empty list.

The order history is the same `OrderHistoryResponse` the owner already gets from `GET /orders/{n}/history`, so
its `correlationId` is nothing new here. Leaving it out of a file while the API returns it would protect nothing.

Not in the files at all: application logs and traces, the outbox and consumer deduplication tables, and the
sign-in throttle's rows. Those are the platform's plumbing rather than records about an account, and most are
not keyed to one. So **this is a copy of what the services hold as records about a customer, not a legal claim
of completeness.** Whether it meets a particular regulator's definition is a question for someone who can
answer it.

### The token is enough; no password is asked for

Closing an account asks for the password again, because that cannot be undone. Downloading can be repeated and
changes nothing, so it takes the same token as every other read. That has a real cost: a stolen access token now
gets everything in one request instead of several, and it reaches the kept email bodies, which no customer read
exposed before. Tokens last 15 minutes ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md)), and nearly all
of this was already readable by that token. If the emails prove to be the sensitive part, a password prompt on the
user service's export alone is a small change.

### A file that stops short must not look complete

The access-log reader takes a limit, so the export asks with `DataExport.ALL_STAFF_READS`, far beyond what its
retention ([ADR 0026](0026-deleting-an-audit-record-is-a-decision.md)) can hold for one customer. Emails do the
same against their ninety days ([ADR 0052](0052-forgetting-an-email-safely.md)). The business tables were
never paged. A page size here would have produced a file that says it is everything and is not.

Order history is read one order at a time. It is one customer's orders, on a request nobody makes often, behind
the gateway's rate limit, so that is left simple rather than optimised for a load it will not see.

### A closed account has nothing to download

The user service answers 401 to a closed account, like every other read there: the identity is gone, so there is
nothing identifying to export. The other four still answer until the token expires, with the anonymous records
they kept, because refusing would protect nobody. After that there is no way to sign in, so **the time to download
is before closing**, and the README says so.

Downloading your own data is not recorded in `operator_access_log`, which is a record of staff reading customers.

## Consequences

- A customer can get a complete copy of their records without asking anybody, and can see the emails we kept for
  the first time.
- A new table holding customer data now has one more place to be listed. Nothing forces that: a test can prove what
  is in a file, not what should have been.
- A client wanting everything makes five calls and keeps five files.

## What this leaves open

- **The inventory service keeps no access log, and its one staff read of a customer is broken.** Its file has no
  `staffAccess` section because there is nothing to read. That is a gap and not a decision. Writing this export also
  found that `GET /api/v1/inventory/reservations?customerId=` answers 500 for any customer with a reservation: it
  reads lazy lines outside a transaction, and no test ever called it with one. It is deliberately left broken here,
  and the export has its own finder. Fixing only the crash would turn it into a working read of a customer's data
  that nothing records, against [ADR 0025](0025-record-what-an-operator-reads.md). The fix and the log belong in one
  change.
- **A download leaves no trace the customer can see.** A stolen token's export shows up in request logs and nowhere
  else. An email saying "your data was downloaded" is the obvious answer if that starts to matter.
- **No web page offers it.** This is the API; there is no customer front end in this repository to put a button on.
