# Architecture decision records

One file per decision that was not obvious, written when it was made. Each records what was decided,
what else was on the table, and what the decision costs — the last part being the one that matters
when someone revisits it in six months.

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-multi-module-monorepo.md) | One multi-module Maven repo, not seven repos | Accepted |
| [0002](0002-spring-boot-and-cloud-versions.md) | Spring Boot 4.0.8, not 4.1.x | Accepted |
| [0003](0003-database-per-service.md) | A database per service, sharing one instance locally | Accepted |
| [0004](0004-derived-flash-sale-phase.md) | Flash-sale liveness is derived, never stored | Accepted |
| [0005](0005-catalog-owns-no-stock.md) | Catalog holds no stock count | Accepted |
| [0006](0006-conditional-update-prevents-overselling.md) | A conditional UPDATE, not a lock, prevents overselling | Accepted |
| [0007](0007-reservations-with-two-path-expiry.md) | Reservations expire lazily *and* on a schedule | Accepted |
| [0008](0008-atomic-counters-for-caps-and-allocations.md) | Allocations and per-customer caps are their own atomic counters | Accepted |
| [0009](0009-no-transaction-across-a-network-call.md) | No database transaction spans a call to another service | Accepted |
| [0010](0010-refusal-and-silence-are-different-failures.md) | A refusal and a timeout are different failures | Accepted |
| [0011](0011-order-owns-no-prices.md) | Prices come from catalog, never from the request | Accepted |
| [0012](0012-asynchronous-checkout.md) | The checkout is asynchronous and returns 202 | Accepted |
| [0013](0013-orchestrated-saga.md) | The saga is orchestrated by the order service | Accepted |
| [0014](0014-idempotency-by-state-machine.md) | Consumers dedupe via the state machine, for now | Accepted, interim |
| [0015](0015-simulated-payment-provider.md) | The payment provider is simulated, keyed on the amount | Accepted |
