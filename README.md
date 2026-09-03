# FlashCart

**A distributed, high-concurrency commerce platform** — an e-commerce backend built around the one
problem that makes flash sales hard: ten thousand people trying to buy the last five hundred units
at the same instant, without a single one of them being oversold.

Seven Spring Boot services behind an API gateway, talking over Kafka, backed by PostgreSQL and
Redis. Built in phases; see [Roadmap](#roadmap) for what is done and what is next.

---

## Architecture

```
                    ┌─────────────────┐
                    │   API Gateway   │   :18080
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ↓              ↓              ↓
        ┌───────────┐  ┌────────────┐  ┌────────────┐
        │  Catalog  │  │   Order    │  │   User     │
        │  :18081   │  │   :18082   │  │   :18083   │
        └─────┬─────┘  └──────┬─────┘  └────────────┘
              │               │
              ↓               ↓
        ┌───────────┐   ┌──────────────┐
        │   Redis   │   │ Kafka/Event  │
        │  :16379   │   │ Bus  :19092  │
        └───────────┘   └──────┬───────┘
                               │
                ┌──────────────┼──────────────┐
                ↓              ↓              ↓
          ┌──────────┐   ┌──────────┐   ┌──────────┐
          │ Payment  │   │Inventory │   │ Shipping │
          │  :18084  │   │  :18085  │   │  :18086  │
          └──────────┘   └──────────┘   └──────────┘
                │              │              │
                └──────────────┼──────────────┘
                               ↓
                    PostgreSQL  :15432
```

Every host port sits in the 15000–19000 range so the whole stack coexists with anything already
listening on 5432, 6379 or 8080.

### Service boundaries

| Service       | Owns                                                            | Status                |
|---------------|-----------------------------------------------------------------|-----------------------|
| **gateway**   | Routing, correlation IDs, the edge                              | ✅ Phase 1            |
| **catalog**   | Products, categories, flash-sale definitions and pricing        | ✅ Phase 2            |
| **inventory** | Stock levels, reservations, reservation expiry                  | ✅ Phase 3            |
| **order**     | The order aggregate and its state machine                       | ✅ Phase 4            |
| **payment**   | Authorisation, capture, saga compensations                      | ✅ Phase 6            |
| **shipping**  | Shipment creation and carrier tracking                          | ✅ Phase 6            |
| **user**      | Accounts, addresses, authentication                             | ⬜ Phase 4            |
| **common**    | Order state machine, event contracts, error envelope, MDC       | ✅ Phase 1            |

The boundaries are drawn so the number that matters most has exactly one owner. **Catalog holds no
stock count.** How many units are left is inventory's data; a copy in the catalog would give the one
number a flash sale cannot afford to get wrong two sources of truth.

See [`docs/architecture.md`](docs/architecture.md) for the event flow and the order state machine,
and [`docs/adr/`](docs/adr/) for the decisions behind the structure.

---

## Running it

Requires Docker and JDK 21+. Maven comes from the wrapper.

```bash
docker compose up --build
```

Eleven containers: PostgreSQL, Redis, Kafka, Kafka UI, and the seven services. Everything reports
healthy in about a minute on a warm cache.

The catalog starts under the `demo` profile, so it comes up with a browsable catalog and a flash
sale that is already live:

```bash
curl -s http://localhost:18080/api/v1/flash-sales/active
```

```bash
curl -s 'http://localhost:18080/api/v1/products?status=ACTIVE&sort=name&direction=asc'
```

```
AUD-HP-001   Aurora Over-Ear Headphones    base=299   effective=179   FLASH SALE
AUD-SP-003   Echo Field Portable Speaker   base=89.5  effective=89.5
HOM-BL-001   Lumen Smart Bulb (4-pack)     base=59    effective=39    FLASH SALE
WEA-WT-001   Meridian Smartwatch           base=449   effective=299   FLASH SALE
```

### Infrastructure only

To run services from your IDE against the real backing stores:

```bash
docker compose up -d postgres redis kafka kafka-ui
```

Each service's `application.yml` already defaults to the published host ports.

### Handy URLs

| What                    | Where                                          |
|-------------------------|------------------------------------------------|
| Gateway                 | http://localhost:18080                         |
| Catalog Swagger UI      | http://localhost:18081/swagger-ui.html         |
| Gateway's routing table | http://localhost:18080/actuator/gateway/routes |
| Kafka UI                | http://localhost:18090                         |

---

## The catalog API

All paths are reachable through the gateway on `:18080` and directly on `:18081`.

### Products

| Method   | Path                          | Notes                                              |
|----------|-------------------------------|----------------------------------------------------|
| `GET`    | `/api/v1/products`            | Paged. Filters: `status`, `categoryId`, `categorySlug`, `q`. Page size capped at 100. |
| `GET`    | `/api/v1/products/{id}`       |                                                    |
| `GET`    | `/api/v1/products/sku/{sku}`  | The lookup other services use                      |
| `GET`    | `/api/v1/products/slug/{slug}`|                                                    |
| `POST`   | `/api/v1/products`            | Defaults to `DRAFT`                                |
| `PUT`    | `/api/v1/products/{id}`       | Send `expectedVersion` to get a 409 instead of a lost update |
| `DELETE` | `/api/v1/products/{id}`       | Archives; never deletes                            |

Every product response carries an **`effectivePrice`** that already accounts for any live flash
sale, plus the `offer` behind it. Clients render that field and never recompute the discount, so the
price on the grid and the price at checkout cannot disagree.

### Categories

`GET|POST /api/v1/categories`, `GET /api/v1/categories/{idOrSlug}` (id *or* slug),
`PUT|DELETE /api/v1/categories/{id}`. Deleting a category that still holds products is refused.

### Flash sales

| Method   | Path                                    | Notes                                        |
|----------|-----------------------------------------|----------------------------------------------|
| `GET`    | `/api/v1/flash-sales/active`            | Live right now — derived, never stale        |
| `GET`    | `/api/v1/flash-sales/upcoming`          |                                              |
| `GET`    | `/api/v1/flash-sales/{idOrSlug}`        |                                              |
| `POST`   | `/api/v1/flash-sales`                   | Created `DRAFT`; sells nothing yet           |
| `POST`   | `/api/v1/flash-sales/{id}/items`        | Refused once the sale is live                |
| `POST`   | `/api/v1/flash-sales/{id}/schedule`     | Approves it to go live when its window opens |
| `POST`   | `/api/v1/flash-sales/{id}/cancel`       | Prices revert on the next read               |

Whether a sale is live is **derived from its window on every read**, not stored and swept by a
scheduler — every second a sweeper is late is a second the storefront sells at the wrong price.
A sale price at or above list price is rejected, so "on sale" always means cheaper.

---

## The inventory API

The service that must not oversell. All paths are on `:18080` through the gateway, or `:18085`
directly.

### Reservations

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/api/v1/inventory/reservations` | Hold stock. All-or-nothing across lines, idempotent on `reservationKey` |
| `GET`  | `/api/v1/inventory/reservations/{key}` | |
| `GET`  | `/api/v1/inventory/reservations?customerId=` | |
| `POST` | `/api/v1/inventory/reservations/{key}/commit` | Payment landed — units leave the warehouse |
| `POST` | `/api/v1/inventory/reservations/{key}/release` | Abandoned, declined, cancelled |

```bash
curl -X POST http://localhost:18080/api/v1/inventory/reservations -H 'Content-Type: application/json' -d '{"reservationKey":"order-1001","customerId":"cust-42","flashSaleId":null,"lines":[{"sku":"AUD-HP-001","quantity":1}]}'
```

`reservationKey` is your order ID and is the idempotency key: retrying returns the **original** hold
rather than taking a second one. At-least-once retries are a certainty, not a hypothetical.

A hold has a TTL (default 15 minutes, server-capped at 1 hour). It resolves three ways — committed,
released, or expired. Committing an expired hold is refused with `409 RESERVATION_NOT_HELD`, because
those units may already belong to someone else; the caller must reconcile rather than confirm an
order it cannot fill.

### Stock and the ledger

| Method | Path | Notes |
|--------|------|-------|
| `GET`  | `/api/v1/inventory/stock/{sku}` | `onHand`, `reserved`, and `available` — render `available` |
| `GET`  | `/api/v1/inventory/stock?skus=A,B,C` | Batched, for a whole product grid |
| `POST` | `/api/v1/inventory/stock` | Start tracking a SKU |
| `POST` | `/api/v1/inventory/stock/{sku}/receive` | Stock arrived |
| `POST` | `/api/v1/inventory/stock/{sku}/adjust` | Signed delta, reason mandatory |
| `GET`  | `/api/v1/inventory/stock/{sku}/movements` | The append-only ledger |

`available = onHand - reserved`. A storefront must render `available`; `onHand` includes units
already promised to someone mid-checkout.

Every change writes a ledger entry carrying the correlation ID of the request that caused it, so
"we are three units short" is answerable. `RELEASED` and `EXPIRED` stay distinct — who gave up and
who ran out of time are different questions.

### Sale allocations

`GET|POST /api/v1/inventory/allocations`, `PUT /api/v1/inventory/allocations/{saleId}/{sku}`.

Catalog *defines* a sale; inventory *enforces* it. A warehouse holding 5,000 units can still run a
sale permitted to move only 500. Registered explicitly for now — Phase 5 replaces the call with an
event.

### Why a reservation can be refused

Three different things all look like "sold out" to a shopper and are entirely different
operationally, so each has its own code:

| Code | Meaning |
|------|---------|
| `INSUFFICIENT_STOCK` | The warehouse does not have the units |
| `SALE_ALLOCATION_EXHAUSTED` | Stock exists, but this sale has sold its allocation |
| `CUSTOMER_LIMIT_EXCEEDED` | This customer has hit the per-customer cap |

Overselling is prevented by a single conditional `UPDATE` whose predicate and mutation are the same
statement — no read-then-write window for a concurrent buyer to slip through — backed by a
`CHECK (reserved <= on_hand)` constraint. See
[ADR 0006](docs/adr/0006-conditional-update-prevents-overselling.md).

---

### The availability gate

Redis sits in front of every reservation as an admission gate, and it has exactly one power: **it can
refuse a request, and it can never approve one.**

Under a flash sale, once the stock is gone, the next fifty thousand requests would each open a
transaction, take a lock, run the conditional `UPDATE`, match nothing and roll back — contending with
the few requests that still have stock to claim. The gate refuses those without touching a
connection.

What it never does is decide a sale. An admitted request still goes to PostgreSQL, and the
conditional `UPDATE` still has the final say, so the cache is allowed to be wrong: too low loses a
sale until the TTL expires, too high wastes one query. Neither can oversell. If Redis is unreachable
the gate returns `UNKNOWN` and everything proceeds exactly as it did before Phase 7.

```yaml
flashcart.inventory.gate.enabled: true   # false takes it out of the path entirely
flashcart.inventory.gate.ttl: 60s        # how long any drift can survive
```

The concurrency test that proves the platform cannot oversell runs **with the gate switched on** —
an optimisation that is only safe when disabled is not safe. See
[ADR 0016](docs/adr/0016-the-gate-may-only-refuse.md).

## The order API

Where a checkout actually happens. Order calls catalog for prices and inventory for stock; it
duplicates neither.

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/api/v1/orders` | Place an order. **202 Accepted**, `CREATED` — the reservation settles on the bus |
| `GET`  | `/api/v1/orders/{orderNumber}` | |
| `GET`  | `/api/v1/orders?customerId=` | Newest first |
| `GET`  | `/api/v1/orders/{orderNumber}/history` | Every transition, with reasons |
| `POST` | `/api/v1/orders/{orderNumber}/cancel` | Cancels and asks inventory to release |

There is deliberately **no endpoint to request payment or to record one failing.** Both were manual
in Phase 4 and are now the saga's, driven by events. Leaving them exposed would give the order
lifecycle two drivers, and the one thing worse than a saga is a saga something else can reach into
halfway through.

```bash
curl -X POST http://localhost:18080/api/v1/orders -H 'Content-Type: application/json' -d '{"idempotencyKey":"checkout-1001","customerId":"cust-42","flashSaleId":null,"lines":[{"sku":"AUD-HP-001","quantity":1}]}'
```

**There is no price field in the request, deliberately.** A checkout that trusts a client-supplied
price is one anyone can discount to zero. The order service asks catalog for each SKU's
`effectivePrice` — flash sale included — and copies it onto the order line, so a historical order
still shows what was actually charged whatever has happened to the product since.

`idempotencyKey` is yours. Retrying returns the original order rather than placing a second — and if
an earlier attempt is still `CREATED`, the retry re-sends the reservation command, which is safe
because inventory is idempotent on the reservation key.

**A checkout is asynchronous.** `POST /orders` returns 202 with a `CREATED` order; inventory answers
on the bus and the saga moves it to `RESERVED` or `CANCELLED`. Watch the order, or poll it. The
trade-off — the shopper no longer gets an instant yes or no — is deliberate and is discussed in
[ADR 0012](docs/adr/0012-asynchronous-checkout.md).

Every response carries `allowedNextStates`, straight from the state machine, so a client renders the
right controls instead of keeping its own copy of rules that will drift.

### When a checkout fails

| Status | Code | Meaning |
|--------|------|---------|
| 404 | `NOT_FOUND` | A SKU is not in the catalog. Nothing was persisted. |
| 409 | `INSUFFICIENT_STOCK` / `SALE_ALLOCATION_EXHAUSTED` / `CUSTOMER_LIMIT_EXCEEDED` | Inventory refused. The order exists and is `CANCELLED`, with the reason recorded. |
| 409 | `ORDER_NOT_CANCELLABLE` | A payment is in flight; resolve it before cancelling. |
| 500 | `INVENTORY_UNAVAILABLE` | Inventory did not answer. The order stays `CREATED` — **retry with the same key**. |

That last row is the interesting one. A refusal and a timeout are different failures: cancelling on a
timeout could strand real stock, and confirming on one could promise stock nobody holds. See
[ADR 0010](docs/adr/0010-refusal-and-silence-are-different-failures.md).

### The state machine

```
CREATED ──▶ RESERVED ──▶ PAYMENT_PENDING ──▶ PAID ──▶ FULFILLING ──▶ SHIPPED ──▶ DELIVERED

PAYMENT_PENDING ──▶ PAYMENT_FAILED      ──▶ CANCELLED          (release inventory)
RESERVED        ──▶ RESERVATION_EXPIRED ──▶ CANCELLED          (release inventory)
PAYMENT_PENDING ──▶ PAYMENT_TIMEOUT     ──▶ PAID | CANCELLED   (reconciliation decides)
```

Phase 4 drives `CREATED` through `PAYMENT_PENDING` and every compensation below it; `PAID` onward
arrives with payment in Phase 6. Transitions go through `OrderStateMachine`, so an illegal move —
an expiry timer firing after the charge settled — is rejected by the machine rather than by a caller
remembering to check. Since Phase 8 that is the second line of defence rather than the only one:
duplicates are caught earlier and explicitly by `processed_events`, which means the state machine
declining something now signals a real anomaly instead of routine redelivery.

Compensation is a persisted state, not a side effect: a declined payment walks
`PAYMENT_PENDING → PAYMENT_FAILED → CANCELLED`, and the history says which it was.

---

## Payment and shipping

Neither service has an endpoint that *does* the thing it is named after. Payment is only ever
initiated by a `RequestPayment` command on the bus, and a shipment only by `CreateShipment` — so the
one operation that moves money and the one that sends real goods each have exactly one entry point.
Both expose reads.

`GET /api/v1/payments/order/{orderNumber}`, `GET /api/v1/payments/{id}`,
`GET /api/v1/shipments/order/{orderNumber}`, `GET /api/v1/shipments/{trackingNumber}`, plus manual
`dispatch` and `deliver` transitions a warehouse operator drives.

### Making a payment fail on purpose

The provider is simulated and picks its outcome from the **amount's cents**, because a real sandbox
approves everything and cannot be made to time out on cue — which would leave the compensation paths,
the most valuable thing in Phase 6, untested end to end.

| Amount ends in | Outcome | What the saga does |
|---|---|---|
| `.13` | declined | `PAYMENT_FAILED` → releases the stock → `CANCELLED` |
| `.99` | provider timeout | `PAYMENT_TIMEOUT` — and releases **nothing** |
| anything else | approved | `PAID` → commits stock, books shipment → `FULFILLING` |

That middle row is the interesting one. A decline is decisive, so the stock is safe to return; a
timeout means the charge may still land, and releasing would risk selling the same unit twice and
then owing a refund. The thresholds are configurable and published on
`GET /api/v1/payment/_info`, so these docs cannot drift from the code.

### Errors

Every service returns the same envelope, so a client parses failures identically no matter which hop
produced them:

```json
{
  "timestamp": "2026-08-27T13:02:20.444486820Z",
  "status": 404,
  "code": "NOT_FOUND",
  "message": "Product 00000000-0000-4000-8000-0000000000ff not found",
  "path": "/api/v1/products/00000000-0000-4000-8000-0000000000ff",
  "correlationId": "e2e-check-003"
}
```

Clients branch on `code`, not on the status — HTTP statuses are too coarse to tell "sold out" from
"reservation expired". Validation failures add a `fieldErrors` array.

### Correlation IDs

The gateway stamps `X-Correlation-Id` on every inbound request (honouring one you supply), forwards
it downstream, and echoes it back. Each servlet service puts it in the SLF4J MDC, so one checkout
fanning out to inventory, payment and shipping produces log lines you can actually join:

```
ERROR [flashcart-catalog,d63d883f-ba11-4cb7-8d5b-a6e398283b59] ...
```

---

## The bus, the outbox, and duplicates

Persisting a state change and publishing an event are two systems, and one can fail without the
other. A crash between the two leaves an order in `PAID` that nothing was ever told about: the saga
stops, silently, and the only evidence is an order that never ships.

So nothing publishes to Kafka directly. `OutboxEventPublisher` writes the message into
`outbox_messages` **inside the caller's transaction** — the state change and the intent to publish
commit together or not at all. A scheduled relay then claims unpublished rows with
`SELECT ... FOR UPDATE SKIP LOCKED`, sends them, waits for the broker to acknowledge, and only then
marks them published. A relay that dies mid-send simply retries; the worst case is a duplicate, never
a loss.

Duplicates are then the consumer's problem, and they are certain — a rebalance alone redelivers.
Every listener claims `(event_id, consumer)` in `processed_events` and does its work in the same
transaction, so a handler that rolls back releases its claim and will be retried rather than being
recorded as done. The claim is per consumer, because several services legitimately handle the same
message and a single "seen" flag would let the first one to arrive suppress it for everyone.

```yaml
flashcart.outbox.enabled: true              # false publishes directly, for comparison
flashcart.outbox.relay.fixed-delay: PT1S    # added latency on every message
```

Both tables grow without bound today; retention is Phase 10's problem and is noted as debt in
[ADR 0017](docs/adr/0017-outbox-and-processed-events.md).

## Building and testing

```bash
./mvnw verify
```

Two tiers, deliberately separated:

- **Unit and slice tests** (`*Test`, surefire) — no Docker, no network. The fast loop.
- **Integration tests** (`*IT`, failsafe) — real PostgreSQL via Testcontainers, driven over real
  HTTP. Not H2: the schema leans on `timestamptz`, `numeric(12,2)`, partial indexes and check
  constraints, and an in-memory dialect that quietly accepts different semantics would give a green
  build for a schema that exists nowhere real.

```bash
./mvnw verify -DskipITs   # skip the Docker-dependent tier
```

CI runs both tiers, then builds all seven images, brings the whole compose stack up, and smoke-tests
it through the gateway — because a green unit suite proves nothing about the routing table.

---

## Stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.8 |
| Spring Cloud | 2025.1.3 (Gateway 5.0.3) |
| PostgreSQL | 17 |
| Redis | 7 |
| Kafka | 3.9 (KRaft, no ZooKeeper) |
| Migrations | Flyway |
| Tests | JUnit 5, AssertJ, Testcontainers 2 |

Boot 4.0.8 rather than the newer 4.1.x is deliberate: `spring-cloud-dependencies:2025.1.3` pins
4.0.8, and the gateway is load-bearing enough that the tested pairing beats the minor bump. See
[ADR 0002](docs/adr/0002-spring-boot-and-cloud-versions.md).

---

## Roadmap

| Phase | Scope                                       | Status |
|-------|---------------------------------------------|--------|
| 1     | Architecture + service skeletons            | ✅     |
| 2     | Catalog                                     | ✅     |
| 3     | Inventory: reservations, locking, expiry    | ✅     |
| 4     | Orders + the state machine                  | ✅     |
| 5     | Kafka event architecture                    | ✅     |
| 6     | Payment + saga                              | ✅     |
| 7     | Redis + concurrency                         | ✅     |
| 8     | Outbox + idempotency                        | ✅     |
| 9     | Observability                               | ⬜     |
| 10    | Load testing                                | ⬜     |
| 11    | Failure injection                           | ⬜     |
| 12    | Documentation + architecture diagrams       | ⬜     |

Each phase deliberately leaves seams for what follows: the order state machine and the event
contracts already live in `flashcart-common` and are unit-tested, every service already has its own
database, and Redis and Kafka are already running with nothing yet using them.

Phase 3 leaves two in particular. Reservation expiry is where Phase 5 will publish
`ReservationExpired` so the order service can move its own state machine; and the
`ATOMIC_UPDATE` / `PESSIMISTIC_LOCK` strategy switch exists so Phase 10 can measure the two under
load, and Phase 7 could measure Redis against both, rather than anyone asserting which is faster.
