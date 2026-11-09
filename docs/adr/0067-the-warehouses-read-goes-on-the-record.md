# 0067 — The warehouse's read goes on the record

**Status:** Accepted · **Date:** 2026-11-06 · **Phase:** post-roadmap · **Builds on [ADR 0025](0025-record-what-an-operator-reads.md), [ADR 0026](0026-deleting-an-audit-record-is-a-decision.md), [ADR 0034](0034-who-read-my-data.md), [ADR 0038](0038-one-role-did-too-much.md) and [ADR 0056](0056-a-customer-sees-that-staff-looked.md)**

## Context

`GET /api/v1/inventory/reservations?customerId=` had two faults, and each one hid the other.

**It answered 500 for any customer who had a reservation.** The finder did not fetch the lazy `lines`,
`ReservationResponse.from` read them after the transaction had ended, and open-in-view is off. A customer with
nothing held got an empty 200. No test called it with data, and `scripts/endpoint-coverage.py` counted it covered
because another request used the same path.

**It was a staff read of a customer's data with nowhere to be recorded.** Order, payment, shipping and user each
keep an `operator_access_log`. Inventory did not. The only reason this read had never disclosed anything unrecorded
is that it crashed whenever there was anything to disclose. Fixing the crash alone would have turned it into exactly
what ADR 0025 exists to prevent.

So the fix and the log are one change.

## Decision

### Inventory keeps the same log as the other four

- `V8__operator_access_log.sql` adds the same table and indexes, in this service's own database, so a failure to
  record is a failure to read.
- The retention sweep ([ADR 0026](0026-deleting-an-audit-record-is-a-decision.md)) deletes nothing unless
  `flashcart.audit.retention` is set. Compose sets `P365D`, as it does for the others.
- `GET /api/v1/inventory/_access-log` is support's reader ([ADR 0034](0034-who-read-my-data.md)).
- `GET /api/v1/inventory/_access-log/mine` is the customer's: that staff looked, what at and when, and not who
  ([ADR 0056](0056-a-customer-sees-that-staff-looked.md)).
- The inventory file from [ADR 0066](0066-your-data-as-a-file.md) gains the `staffAccess` section the other four
  already have.

### The read is recorded first, then fetches its lines

The action is `READ_RESERVATION_LIST`, shown to the customer as "A member of staff viewed your reservations". It is
written before the query runs. If it cannot be written the read fails, and if the query then fails the record stands,
because somebody did ask. Looking at a customer with nothing held is still looking, so it is recorded too. A member of
staff reading their own is a customer reading their own, so it is not. A blank `customerId` is now a 400, or it would
write a row saying somebody read nobody.

`findWithLinesByCustomerIdOrderByCreatedAtDesc` replaces the lazy finder. The old one had no other caller, and leaving
it beside the new one would leave the same mistake available to the next reader.

### This read stays the warehouse's, so it does not go through `CustomerDataAccess`

The other services record a listing through `CustomerDataAccess.subjectOf`. That also checks
`maySeeAnotherCustomer`, which `SUPPORT` and `OPERATOR` hold and `WAREHOUSE` does not. Here the filter admits
`WAREHOUSE` and `OPERATOR`, as [ADR 0038](0038-one-role-did-too-much.md) assigned it, so using `subjectOf` would have
refused the warehouse from one layer below the filter, with a 403 that looks like any other. That is the mismatch ADR
0038 warned about. This change is about recording the read, not about moving it to another role, so the controller
writes to `OperatorAccessLog` itself and the set of roles that can make the read does not change. Inventory has no
`CustomerDataAccess` bean, because nothing here needs one.

### The warehouse's wildcard had to go

The warehouse's rule was `/api/v1/inventory/**`, justified in its comment because everything under that prefix was
warehouse work. The access log on that prefix ended that. Role rules can only widen, so nothing could carve the log
out of the wildcard. The reader's own check would still have refused the warehouse, but as the only check left.

The rule now names the warehouse's paths: stock, allocations and reservations. `_access-log` goes to `SUPPORT`, as it
does in shipping, where the warehouse needs neither. `_access-log/mine` is signed-in by exact match. Naming paths can
close something by accident, so a test sends a warehouse token to all fifteen of its endpoints and asserts that none
gets a 403.

## Verified

- `OperatorAccessLogIT`, 19 tests on real PostgreSQL, each read against a customer who really has a reservation: a
  reservation with two lines comes back as one, with both lines, newest first, and nobody else's. The row names the
  reader, the action and the customer, and carries the correlation id. Tokens holding exactly one role show who is
  admitted and who is refused at each path.
- `DataExportIT`: after a real warehouse read, the customer's file has one `staffAccess` entry with the four fields
  and no staff id. A customer nobody looked at has an empty list. Removing the section turns that test red.
- Mutations, each one predicted before it ran and each caught by the test that names it: lines not fetched (the
  original 500); read not recorded; recorded under the wrong customer; recorded after the read; a recording failure
  swallowed; a member of staff's own read recorded; a blank customer allowed; the capability check added, which turns
  the warehouse away; the customer's reader not opened; support's rule gone; the warehouse's wildcard back; the
  warehouse losing the stock root, its allocations or the reservations root; the action's description gone; the sweep
  ignoring its window; the sweep not scheduled.
- One mutation stayed green and taught something. Removing `@EnableScheduling` from `InventoryConfig` changed nothing,
  because `OutboxAutoConfiguration` also enables it. Removing `@Scheduled` from the sweep is what the test catches.
- CI, on the compose stack through the gateway: the warehouse reads the CI shopper's real reservations, each with its
  lines; exactly one row appears in inventory's database; a shopper and the warehouse get 403 where they should;
  support sees both entries; the shopper sees the read with no staff id; and the service logs that it keeps records
  for 365 days, which proves compose's setting reached it.

## Consequences

**Good.** The read works, and it cannot work without being recorded. A customer's view of who looked now covers
inventory, and so does support's.

**Accepted.** "Who read my data" is five calls now, not four, for the reason ADR 0034 gave.

## What this leaves open

- **`GET /api/v1/inventory/reservations/{reservationKey}` is another staff read, and it is not recorded.** It returns
  the customer's id and lines to the same two roles. This change records the listing only. Nothing in the platform
  calls it except tests, so recording it would be cheap, under a `READ_RESERVATION` action like shipping's single read.
- **Support cannot make this read, and the warehouse can.** Everywhere else, reading another customer's data is
  support's work. Whether the warehouse needs a customer's whole list, or only stock and a single reservation, is a
  question about the job. This change does not settle it.
- **The order service's log has no retention sweep.** Payment, shipping, user and now inventory have one; order never
  did, so its rows are kept for ever whatever `AUDIT_RETENTION` says.

*The order service's sweep was since added by [ADR 0069](0069-the-order-services-log-was-kept-for-ever.md).*
