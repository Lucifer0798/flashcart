# 0016 — The Redis availability gate may only ever refuse

**Status:** Accepted · **Date:** 2026-09-03 · **Phase:** 7

## Context

Every reservation reaches PostgreSQL. Under a flash sale that is the intended behaviour for the
requests that can succeed, and pure waste for the ones that cannot: once a thousand units are gone,
the next fifty thousand requests each open a transaction, take a row lock, run a conditional
`UPDATE`, match zero rows, and roll back. They contend with each other and with the handful of
requests that still have stock to claim.

The obvious fix — keep the count in Redis and serve reservations from it — is the one that
reintroduces overselling, because it makes a cache authoritative over money.

## Decision

Redis sits in front of the database as an admission gate with one asymmetric power: **it may say
"definitely not", and it may never say "yes".**

`AvailabilityGate.tryAdmit` returns one of three answers. `REFUSED` short-circuits the request.
`ADMITTED` means only "not obviously impossible — go ask PostgreSQL", and the conditional `UPDATE` of
ADR 0006 still decides. `UNKNOWN` — no warmed key, or Redis unreachable — also proceeds to
PostgreSQL. Two of the three outcomes are identical in effect; the gate exists solely for the third.

The counter is decremented by a Lua script so the check and the decrement are one atomic step, and it
is treated as disposable: warmed from the database on a miss, released when a hold is rejected or
expires, and *invalidated* rather than patched when stock is received or adjusted. It carries a TTL,
so any drift is temporary by construction.

## Alternatives considered

**Redis as the authority, reconciled to PostgreSQL later.** The fastest design and the one this
project exists to argue against. Reconciliation cannot un-sell a unit that was never there.

**A local in-process counter.** No network hop at all, but it is per-instance, so its accuracy
degrades exactly as you scale out — worst precisely under the load that motivates it.

**Nothing; let PostgreSQL absorb it.** Correct, and where Phase 6 stood. The gate is an optimisation,
which is why it is built so that removing it changes throughput and nothing else.

## Consequences

**Good.** Doomed requests are refused without touching a connection, so the pool stays available to
requests that can still succeed. Correctness is untouched: the gate cannot admit a reservation the
database would have refused, because the database still refuses it. Redis failing degrades the system
to Phase 6 behaviour rather than breaking it — every error path collapses to `UNKNOWN`.

**Bad.** Drift is now possible in both directions, and both had to be shown harmless. Drift low
refuses a sale that could have been made — a lost order, self-healing within the TTL, and the reason
the TTL is short. Drift high admits a request PostgreSQL then refuses — one wasted query, exactly the
cost of not having a gate. Neither can oversell, and that asymmetry is the whole design.

There is also a second place stock levels are represented, which is a real maintenance cost. It is
bounded by keeping the gate write-through only on the reservation path and invalidating everywhere
else: no code outside `RedisAvailabilityGate` reasons about the cached number.

`InventoryConcurrencyIT` therefore runs **with the gate enabled**, still asserting that exactly the
available number of concurrent buyers succeed. The anti-oversell proof has to hold with the
optimisation in the path, or the optimisation is not safe to ship.
