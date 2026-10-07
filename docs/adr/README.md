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
| [0014](0014-idempotency-by-state-machine.md) | Consumers dedupe via the state machine, for now | Superseded by 0017 |
| [0015](0015-simulated-payment-provider.md) | The payment provider is simulated, keyed on the amount | Accepted |
| [0016](0016-the-gate-may-only-refuse.md) | The Redis availability gate may only ever refuse | Accepted |
| [0017](0017-outbox-and-processed-events.md) | A transactional outbox out, a processed-events table in | Accepted |
| [0018](0018-metrics-answer-questions-the-logs-cannot.md) | Instrument the silences, not the traffic | Accepted |
| [0019](0019-measure-or-say-you-cannot.md) | Report what the measurement supports, and no more | Accepted |
| [0020](0020-a-trace-must-survive-the-outbox.md) | A trace has to survive the outbox | Accepted |
| [0021](0021-the-client-does-not-say-who-it-is.md) | The client does not get to say who it is | Accepted |
| [0022](0022-being-signed-in-is-not-being-a-warehouse.md) | Being signed in is not being a warehouse | Accepted |
| [0023](0023-a-customer-may-see-their-own.md) | A customer may see their own | Accepted |
| [0024](0024-the-development-operator-is-not-schema.md) | The development operator is not schema | Accepted |
| [0025](0025-record-what-an-operator-reads.md) | Record what an operator reads | Accepted |
| [0026](0026-deleting-an-audit-record-is-a-decision.md) | Deleting an audit record is a decision | Accepted |
| [0027](0027-the-outbox-hop-is-a-span.md) | The outbox hop is a span | Accepted |
| [0028](0028-looking-is-not-acting.md) | Looking is not acting | Accepted |
| [0029](0029-an-operator-read-was-a-silence.md) | An operator read was a silence | Accepted |
| [0030](0030-cancelling-a-paid-order.md) | Cancelling a paid order | Accepted, amended by 0033 and 0040 |
| [0031](0031-when-nobody-answers.md) | When nobody answers | Accepted, amended by 0041 |
| [0032](0032-an-order-that-can-finish.md) | An order that can finish | Accepted |
| [0033](0033-shipped-did-not-mean-shipped.md) | SHIPPED did not mean shipped | Accepted |
| [0034](0034-who-read-my-data.md) | Who read my data | Accepted |
| [0035](0035-nothing-behind-the-check.md) | Nothing behind the check | Accepted |
| [0036](0036-anyone-could-set-the-price.md) | Anyone could set the price | Accepted |
| [0037](0037-the-last-one-unargued.md) | The last one unargued | Accepted |
| [0038](0038-one-role-did-too-much.md) | One role did too much | Accepted |
| [0039](0039-nothing-ever-ran-without-operator.md) | Nothing ever ran without OPERATOR | Accepted |
| [0040](0040-a-cancelled-sale-gives-the-units-back.md) | A cancelled sale gives the units back | Accepted |
| [0041](0041-the-alert-that-forgot.md) | The alert that forgot | Accepted |
| [0042](0042-every-alert-went-quiet-together.md) | Every alert went quiet together | Accepted |
| [0043](0043-an-alert-nobody-receives.md) | An alert nobody receives | Accepted |
| [0044](0044-a-queue-for-what-is-gone.md) | A queue for what is gone | Accepted, amended by 0045 |
| [0045](0045-told-means-yours.md) | Told means yours | Accepted |
