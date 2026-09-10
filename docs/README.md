# FlashCart documentation

Four documents and twenty decision records. This page is the map.

## Start here

| If you want to… | Read |
|---|---|
| Understand what the platform is and run it | [the README](../README.md) |
| Understand how the pieces fit and why | [architecture.md](architecture.md) |
| See whether the anti-oversell claim survives concurrency | [load-testing.md](load-testing.md) |
| See whether it survives losing a dependency | [`chaos/inject.sh`](../chaos/inject.sh) |
| Understand a specific decision | the ADR index below |

## The one idea

Everything here is downstream of a single problem: **two shoppers, one unit, the same millisecond.**

The answer is one SQL statement — `UPDATE ... WHERE available >= ?` — and every other piece of this
platform exists to protect it, work around it, or prove it holds. Redis refuses doomed requests
before they reach it. The outbox makes sure a decision and its announcement commit together. The
saga unwinds when something downstream says no. The load test proves the statement holds under two
thousand simultaneous buyers, and the chaos suite proves it holds when Redis, Kafka, or a whole
service disappears.

If you read one decision record, read
[ADR 0006](adr/0006-conditional-update-prevents-overselling.md).

## Decision records

Grouped by what they are about rather than by number. Chronological order is in
[the ADR index](adr/README.md).

**The number that must never be wrong**
- [0006 — A conditional UPDATE, not a lock, prevents overselling](adr/0006-conditional-update-prevents-overselling.md)
- [0008 — Allocations and per-customer caps are their own atomic counters](adr/0008-atomic-counters-for-caps-and-allocations.md)
- [0016 — The Redis availability gate may only ever refuse](adr/0016-the-gate-may-only-refuse.md)
- [0007 — Reservations expire lazily *and* on a schedule](adr/0007-reservations-with-two-path-expiry.md)

**Who owns which fact**
- [0003 — A database per service](adr/0003-database-per-service.md)
- [0005 — Catalog holds no stock count](adr/0005-catalog-owns-no-stock.md)
- [0011 — Prices come from catalog, never from the request](adr/0011-order-owns-no-prices.md)
- [0004 — Flash-sale liveness is derived, never stored](adr/0004-derived-flash-sale-phase.md)

**Failure is a design input**
- [0009 — No database transaction spans a call to another service](adr/0009-no-transaction-across-a-network-call.md)
- [0010 — A refusal and a timeout are different failures](adr/0010-refusal-and-silence-are-different-failures.md)
- [0012 — The checkout is asynchronous and returns 202](adr/0012-asynchronous-checkout.md)
- [0013 — The saga is orchestrated by the order service](adr/0013-orchestrated-saga.md)
- [0015 — The payment provider is simulated, keyed on the amount](adr/0015-simulated-payment-provider.md)

**Messages, exactly once**
- [0017 — A transactional outbox out, a processed-events table in](adr/0017-outbox-and-processed-events.md)
- [0014 — Consumers dedupe via the state machine *(superseded by 0017)*](adr/0014-idempotency-by-state-machine.md)

**Knowing what it is doing**
- [0018 — Instrument the silences, not the traffic](adr/0018-metrics-answer-questions-the-logs-cannot.md)
- [0020 — A trace has to survive the outbox](adr/0020-a-trace-must-survive-the-outbox.md)
- [0019 — Report what the measurement supports, and no more](adr/0019-measure-or-say-you-cannot.md)

**Who is asking**
- [0021](adr/0021-the-client-does-not-say-who-it-is.md) — The client does not get to say who it is
- [0022](adr/0022-being-signed-in-is-not-being-a-warehouse.md) — Being signed in is not being a warehouse

**Shape of the repository**
- [0001 — One multi-module Maven repo, not seven](adr/0001-multi-module-monorepo.md)
- [0002 — Spring Boot 4.0.8, not 4.1.x](adr/0002-spring-boot-and-cloud-versions.md)

## A note on what these documents claim

Several of them are more interesting for what they decline to say.

[ADR 0019](adr/0019-measure-or-say-you-cannot.md) records **no** throughput ranking between
`ATOMIC_UPDATE` and `PESSIMISTIC_LOCK`, because run-to-run variance on the development machine was
wider than the gap between configurations. The switch is still there and the question is still open,
which is the accurate state of knowledge rather than a tidy one.

[ADR 0014](adr/0014-idempotency-by-state-machine.md) is kept although superseded, because the reason
it was wrong is more useful than the fact that it was replaced.

The load and chaos pages both spend as much space on their own harnesses lying as on the platform,
because in this project the recurring failure was rarely the code. It was checks that could not fail,
measurements that counted the wrong thing, and configuration that read as correct while doing nothing
at all.
