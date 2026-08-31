# 0008 — Sale allocations and per-customer caps are their own atomic counters

**Status:** Accepted · **Date:** 2026-08-28 · **Phase:** 3

## Context

A flash-sale reservation has to satisfy three separate conditions:

1. The warehouse has the units.
2. The sale has not already sold its allocation — a warehouse holding 5,000 units can still be
   running a sale permitted to move only 500.
3. This customer is under the per-customer cap — the anti-scalper rule.

Only the first is a property of stock. The other two are policy, and both are contended by exactly
the traffic they exist to constrain.

## Decision

Give each its own row and its own conditional update, checked in addition to stock rather than
instead of it:

- `sale_allocations` — `reserved_units + committed_units <= allocated_units`, enforced by the same
  conditional-`UPDATE` shape as stock ([ADR 0006](0006-conditional-update-prevents-overselling.md)),
  and by a `CHECK` constraint behind it.
- `customer_sale_limits` — one row per (customer, sale, SKU), charged through
  `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE consumed_units + :quantity <= :limit`.

`consumed_units` counts units currently **held plus already bought**. Committed units keep counting;
released and expired holds decrement. A customer whose hold lapsed is free to try again; one who
actually bought is not.

## Alternatives considered

**Derive the cap by counting the customer's existing reservations.** The obvious implementation, and
defeated by the exact behaviour the cap exists to stop. A scalper firing fifty concurrent requests
has fifty transactions all counting zero prior reservations, all passing, all proceeding. It is the
overselling race again, wearing a different hat.

**Enforce the allocation by decrementing stock instead.** Would mean the sale's limit and the
warehouse's limit share one counter, so a sale ending would have to hand unsold units back — bookkeeping
with no upside, and it destroys the ability to answer "how many did this sale actually move".

**Put the cap in the order service.** It has the customer context, but not the atomicity: it would be
counting its own orders, with the same race, one service further from the data.

## Consequences

**Good.** Each rule is enforced where it can be enforced atomically. The three checks compose:
`InventoryConcurrencyIT` shows sixty simultaneous requests yielding exactly the allocation when the
allocation binds, and exactly the cap when the cap binds. Because the allocation is separate, a sale
can be resized without touching stock, and "what did this sale sell" is a column rather than a query.

**Bad.** A flash-sale reserve is three conditional updates plus an insert rather than one, so it is
meaningfully more work per request than an ordinary reserve — the price of the two policies, and the
first thing to look at if Phase 10 finds the path too slow.

The `ON CONFLICT ... WHERE` guard only covers the update branch; the insert branch (a customer's first
request for a SKU) is guarded by an ordinary comparison in the service. That is safe — both values are
already in hand, so it reads no shared state — but it is a genuine asymmetry, and it is commented at
the call site because it does not look safe at a glance.

Catalog still owns the *definition* of both numbers. Until Phase 5 puts events between the services,
an allocation is registered here explicitly rather than by calling catalog: a synchronous dependency
from the most contended service in the platform to another one is exactly the coupling worth not
having.
