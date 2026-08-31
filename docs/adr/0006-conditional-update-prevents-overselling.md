# 0006 — A conditional UPDATE, not a lock, is what prevents overselling

**Status:** Accepted · **Date:** 2026-08-28 · **Phase:** 3

## Context

Ten thousand people want the last five hundred units. Whatever else the platform does, it must never
confirm an order it cannot fill — a shopper told "sold out" is disappointed, a shopper told
"confirmed" and then "actually, no" costs a refund, a support ticket, and trust.

The natural implementation is the one everybody writes first:

```java
StockItem item = repository.findBySku(sku);
if (item.available() >= quantity) {     // ← check
    item.setReserved(item.getReserved() + quantity);
    repository.save(item);              // ← write
}
```

Between the check and the write is a window. Under a flash sale that window is not a theoretical
risk, it is the normal case: at a hundred concurrent buyers and five units left, a hundred requests
read `available == 5` and a hundred requests pass the check. **No amount of care in the service layer
closes it**, because the race is in the shape of the operation, not in the code around it.

## Decision

Make the predicate and the mutation a single statement:

```sql
update stock_items
   set reserved = reserved + :quantity
 where sku = :sku
   and on_hand - reserved >= :quantity
```

PostgreSQL takes a row lock for the statement's duration, re-evaluates the condition against the
committed row, and either updates one row or matches nothing. A return of `0` means someone else got
there first — during a sale, the ordinary outcome, not an error.

The same shape is used for all three quantity checks a flash-sale reservation must pass: stock,
the sale's allocation, and the customer's cap (see
[ADR 0008](0008-atomic-counters-for-caps-and-allocations.md)).

Backing all of it, `CHECK (reserved <= on_hand)` in the schema. If the application logic were ever
wrong, the database refuses rather than sells the unit twice.

## Alternatives considered

**Optimistic locking (`@Version` + retry).** Correct, and the wrong tool for a flash sale. Optimistic
locking assumes conflict is rare; here conflict *is* the workload. Every buyer of a hot SKU collides,
so almost every request fails its version check and retries, and the retries collide too. It converts
a contended write into a retry storm, and the storm is worst exactly when the sale is busiest.

Optimistic locking is still used in this service — on `StockService`'s admin paths, where writes are
human-driven and rare and two warehouse staff editing the same SKU genuinely should be told.

**Pessimistic locking (`SELECT ... FOR UPDATE`).** Also correct, and measurably worse under load: the
row lock is taken at the read and held until the transaction commits, so every other buyer of that
SKU queues behind everything else this request does. The conditional update holds its lock for one
statement. `SELECT ... FOR UPDATE` is the right tool when a decision genuinely needs to read several
values and then write — which reserving does not.

Kept behind `flashcart.inventory.strategy=PESSIMISTIC_LOCK` so Phase 10 can measure the difference
rather than assert it.

**Serializable isolation.** Correct, and pushes the problem into serialization failures the
application must catch and retry — the retry storm again, with more machinery.

## Consequences

**Good.** Overselling is structurally impossible rather than carefully avoided. Row locks live for
microseconds and never span a network call or a user's think time. Rejection — the common path during
a sale — costs one statement that matches no rows.

**Bad.** The hot path bypasses JPA's entity model, so `StockItem` is only used for reads and admin
writes; anyone adding a field must remember the native queries exist. The `int` return of "rows
affected" is a weak type for "did it work", so every call site has to check it, and a call site that
forgets fails silently. Both are why those queries carry the longest comments in the codebase.

**Verified, not asserted.** `InventoryConcurrencyIT` fires sixty simultaneous requests at twenty
units and asserts that *exactly* twenty succeed — one too many is an oversell, one too few is a lost
sale. That test is the reason to believe any of the above.
