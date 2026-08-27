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
| **inventory** | Stock levels, reservations, reservation expiry                  | ⬜ Phase 3            |
| **order**     | The order aggregate and its state machine                       | ⬜ Phase 4            |
| **payment**   | Authorisation, capture, saga compensations                      | ⬜ Phase 6            |
| **shipping**  | Shipment creation and carrier tracking                          | ⬜ Phase 6            |
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
| 3     | Inventory: reservations, locking, expiry    | ⬜     |
| 4     | Orders + the state machine                  | ⬜     |
| 5     | Kafka event architecture                    | ⬜     |
| 6     | Payment + saga                              | ⬜     |
| 7     | Redis + concurrency                         | ⬜     |
| 8     | Outbox + idempotency                        | ⬜     |
| 9     | Observability                               | ⬜     |
| 10    | Load testing                                | ⬜     |
| 11    | Failure injection                           | ⬜     |
| 12    | Documentation + architecture diagrams       | ⬜     |

Phases 1 and 2 deliberately leave seams for what follows: the order state machine and the event
contracts already live in `flashcart-common` and are unit-tested, every service already has its own
database, and Redis and Kafka are already running with nothing yet using them.
