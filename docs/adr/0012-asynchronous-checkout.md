# 0012 — The checkout is asynchronous, and says so with 202

**Status:** Accepted · **Date:** 2026-09-02 · **Phase:** 5

## Context

Phase 4's checkout called inventory over HTTP and waited. The shopper learned immediately whether
they had the item, which is the answer they most want — and is exactly the wrong shape for the
traffic this platform is built for.

Ten thousand simultaneous checkouts become ten thousand simultaneous open connections, all waiting
on the single most contended service in the system. The queue forms in a connection pool, where it
is expensive, bounded, and fails badly when it overflows.

## Decision

`POST /api/v1/orders` prices the basket, persists the order as `CREATED`, publishes
`ReserveInventory`, and returns **202 Accepted**. Inventory replies on the bus and the saga moves the
order to `RESERVED` or `CANCELLED`. Clients poll the order, or watch it.

201 would be a lie: it says a resource was created in the state the caller asked for, and whether
this one got its stock is not known yet.

## Alternatives considered

**Keep the reserve synchronous, events for everything after.** Tempting — it preserves the instant
answer where it matters most. Rejected because it leaves the worst load characteristic exactly where
it is: the reserve is *the* contended call, so making everything except that call asynchronous
optimises the parts that were never the problem.

**Long-poll or stream the outcome to the client.** A better user experience and orthogonal to this
decision — the server-side shape is the same either way. Worth doing on top; it is a transport
choice, not an architecture one.

**Synchronous with a queue in front (bulkhead).** Bounds the damage without changing the model, and
is genuinely a reasonable middle ground. It amounts to running a queue anyway, without Kafka's
durability, replay, or the ability for a second consumer to appear later.

## Consequences

**Good.** A checkout responds in the time it takes to write one row. Load spikes queue in Kafka,
where a backlog is ordinary and nothing holds a thread while it waits. Inventory can be restarted,
scaled or briefly unavailable without any checkout failing — the commands wait.

**Bad, and worth being honest about.** The shopper no longer gets an instant yes or no, and during a
flash sale that is precisely the moment they want one. Every client is now a state machine that has
to handle "not yet".

The window between persisting the order and publishing the command is a real hole: a crash in
between leaves an order in `CREATED` with no command. Mitigated by `place()` re-publishing when it
finds an existing `CREATED` order, and closed properly by Phase 8's outbox.

`202` also means the HTTP status no longer carries the business outcome, so a client that only looks
at status codes will think every checkout succeeded. That is the cost of the model, and it is why
`allowedNextStates` is on every response.
