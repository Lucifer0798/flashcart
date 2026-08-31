# 0010 — A downstream refusing and a downstream not answering are different failures

**Status:** Accepted · **Date:** 2026-08-31 · **Phase:** 4

## Context

When the order service asks inventory to hold stock, three things can happen: it holds it, it refuses,
or it says nothing — a timeout, a connection reset, a 502 from something in between.

The tempting simplification is two outcomes: it worked, or it didn't. Most client code is written
that way.

## Decision

Model refusal and silence as different exception types, and handle them differently.

| Outcome | Type | What the order service does |
|---|---|---|
| Held | — | `CREATED → RESERVED` |
| Refused (4xx) | `InventoryRejectedException` | Cancel the order, keeping inventory's own code |
| No answer | `InventoryUnavailableException` | Leave the order `CREATED` and let the caller retry |

## Alternatives considered

**Treat any failure as a refusal and cancel the order.** Wrong, and expensively so. If the reserve
actually landed before the connection dropped, the stock is held by an order that has just been
cancelled — stranded until its TTL expires, during the exact window when it is scarcest.

**Treat any failure as retryable and leave the order open.** Equally wrong in the other direction. A
genuine "sold out" would leave the order sitting in `CREATED` forever, and a customer waiting for
something that is never going to happen.

**Retry automatically inside the client.** Reasonable for the unavailable case, and deferred
deliberately: retries interact with the reservation TTL and with the caller's own timeout, and
Phase 11's failure injection is the right place to tune that rather than guess now. The design is
already retry-safe, which is the part that had to be got right first.

## Consequences

**Good.** The dangerous case is handled as dangerous. Inventory's own codes —
`INSUFFICIENT_STOCK`, `SALE_ALLOCATION_EXHAUSTED`, `CUSTOMER_LIMIT_EXCEEDED` — travel to the client
unflattened, so a shopper's "sold out" and an operator's "which limit did this hit" are the same
response read at different depths.

Retrying is safe because the reservation key is the order id and inventory is idempotent on it: a
retry after silence either creates the hold or returns the one the lost call already made. That
property is what makes the whole approach work, and it was designed into Phase 3 for this reason.

**Bad.** A stranded `CREATED` order needs something to retry it. Today that is the caller re-sending
with the same idempotency key; nothing sweeps them automatically. Worth revisiting alongside Phase 6,
where the same shape of problem — `PAYMENT_TIMEOUT`, silence from a payment provider — needs a
reconciliation job anyway.

The same reasoning is why `PAYMENT_TIMEOUT` exists in the order state machine and is the only state
with two legal exits. Silence is not a "no" there either.
