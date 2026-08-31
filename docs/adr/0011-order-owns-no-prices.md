# 0011 — Prices come from catalog, never from the request

**Status:** Accepted · **Date:** 2026-08-31 · **Phase:** 4

## Context

An order needs a price per line. The client placing it already knows the price — it rendered it on
the product page a moment ago — so passing it in the request body is the obvious, efficient thing.

## Decision

`PlaceOrderRequest` has no price field. The order service asks catalog for each SKU's
`effectivePrice` and uses that. The resolved price is then **copied onto the order line** and stored,
along with the product name.

## Alternatives considered

**Accept the price from the client.** A checkout that trusts a client-supplied price is a checkout
anyone can discount to zero with a modified request. This is not a subtle risk; it is the first thing
anyone tries.

**Store a reference to the catalog product and look the price up when displaying the order.** Then a
price change, or an archived product, silently rewrites history: an order from last month would
display today's price, or fail to display at all. What a customer was charged is a fact about the
past, not a join.

**Have catalog price the whole basket in one call.** Fewer round trips, and a better idea once
baskets get large — but it puts order-shaped logic into catalog, and the current per-SKU call is
already the seam where a batch endpoint would drop in.

## Consequences

**Good.** The price a customer is charged is the price catalog decided, including any live flash-sale
price, and it cannot be tampered with. A historical order shows what was actually bought and actually
charged, whatever has happened to the product since.

**Bad.** Placing an order costs one catalog call per line, which is an N+1 in all but name for a large
basket. Acceptable at flash-sale basket sizes — typically one line — and the fix is a batch endpoint
on catalog rather than a change to this decision.

There is a window between pricing and payment in which catalog's price could change. The order keeps
the price it was placed at, which is the correct answer: the customer agreed to a number, and it is
that number they pay.
