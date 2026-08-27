# 0005 — Catalog holds no stock count

**Status:** Accepted · **Date:** 2026-08-27 · **Phase:** 2

## Context

The catalog service owns products and flash-sale definitions. A flash sale allocates a fixed number
of units — 500 headphones at £179. It is tempting to keep the remaining count next to the allocation,
because the storefront wants to render "312 left" beside the price, and the data is right there.

## Decision

Catalog stores `allocated_units` — *how many this sale may sell* — and never a remaining count.
How many are left is owned solely by the inventory service (Phase 3).

`FlashSaleItem.allocatedUnits` is a statement of intent. Inventory is what enforces it.

## Alternatives considered

**Keep a remaining count in catalog, decremented on each sale.** This is the design that oversells.
The remaining count is the single most contended value in the entire system — thousands of writes a
second against one row at the top of a sale. Putting it in the service whose other job is serving
high-volume cached reads means the write contention and the read load fight over the same connection
pool and the same rows.

**Keep a cached copy in catalog, refreshed from inventory.** Now the number has two owners and a
staleness window, and the storefront can show "in stock" for a product that sold out seconds ago.
Tolerable for a "312 left" badge; catastrophic if anything downstream ever treats it as authoritative
— and something eventually will.

## Consequences

**Good.** One owner for the number that cannot be wrong. Catalog stays read-mostly and cacheable,
which is what makes it survive the traffic spike. Inventory is free to use whatever concurrency
mechanism the problem actually needs — atomic Redis operations, pessimistic row locks, reservations
with expiry — without any of it leaking into the product model.

**Bad.** Rendering a product card with a live stock badge takes two calls, not one. That is a real
cost, paid at the API composition layer (or, later, a read model fed by inventory events) rather than
by weakening the ownership rule.

Until Phase 3 exists, nothing enforces `allocated_units` at all. That is honest: a Phase 2 catalog
that appeared to enforce stock would be lying, and lying about stock is the failure this whole design
is built to avoid.
