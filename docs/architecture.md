# FlashCart architecture

The reference for how the pieces fit. Phase 12 expands this with proper rendered diagrams; this is
the working version that stays accurate as phases land.

---

## The problem being designed around

A flash sale is not "an e-commerce site with a discount". It is a deliberate thundering herd: a
known instant at which traffic jumps by two or three orders of magnitude, aimed at a stock count
small enough that most requests must be refused. Every design decision in this repo is downstream of
three consequences of that.

**1. Overselling is the only unacceptable failure.** A shopper who is told "sold out" is
disappointed. A shopper who is told "confirmed" and then told "actually, no" costs a refund, a
support ticket, and trust. So the system is allowed to be slow, and allowed to refuse, but never
allowed to promise stock it does not have.

**2. The correct answer is usually "no".** Optimising the happy path is the wrong instinct; the
rejection path is the hot path. Refusals must be cheap, and must not consume the same resources the
successful orders need.

**3. Nothing can hold a lock across a human.** Between "add to cart" and "payment confirmed" sits a
person typing a card number and a payment provider taking seconds to answer. Holding a database row
lock across that is how a flash sale takes the database down. Hence reservations with expiry, and a
saga rather than a distributed transaction.

---

## Service boundaries

The split is drawn along **who owns which fact**, not along who reads it.

```
catalog      what a product IS, and what a sale OFFERS      (definitions)
inventory    how many units are LEFT                        (the contended number)
order        what a customer COMMITTED to                   (the aggregate + state machine)
payment      whether money MOVED                            (external, slow, unreliable)
shipping     where the goods ARE
user         who the customer IS
```

The important line is between **catalog** and **inventory**. Catalog says a flash sale allocates 500
units of `AUD-HP-001` at £179. It does not, and must not, track how many of those 500 remain — that
number is written thousands of times a second under contention, and giving it two owners is the
mechanism by which platforms oversell. Catalog's `allocated_units` is a *statement of intent*;
inventory is what enforces it.

### What the gateway is and is not

It routes, and it stamps a correlation ID. That is all, on purpose. It is not a place for business
logic, and it holds no state, so it can be scaled horizontally without coordination.

It runs on WebFlux while every domain service runs on Spring MVC. That asymmetry is deliberate: the
gateway does nothing but wait on downstream I/O, which is exactly the workload a non-blocking stack
wins at, whereas the domain services do blocking JDBC work where a thread-per-request model is
simpler and no slower.

### Database per service

Six databases in one PostgreSQL instance locally, six instances in production. Separate databases
rather than separate schemas because the point of the split is that no service *can* reach into
another's tables — a shared database makes that a matter of discipline instead of permissions.

Every service's database is created on day one, including the five whose schemas arrive in later
phases, so bringing a service to life is a code change and never an infrastructure change.

---

## The order state machine

Lives in `flashcart-common` (`OrderStatus`, `OrderStateMachine`) rather than inside the order
service, because the state names travel on the event bus: inventory, payment and shipping all react
to transitions they do not own.

### Happy path

```
CREATED ──▶ RESERVED ──▶ PAYMENT_PENDING ──▶ PAID ──▶ FULFILLING ──▶ SHIPPED ──▶ DELIVERED
```

### Failure paths

```
PAYMENT_PENDING ──▶ PAYMENT_FAILED      ──▶ CANCELLED     (release inventory)
RESERVED        ──▶ RESERVATION_EXPIRED ──▶ CANCELLED     (release inventory)
PAYMENT_PENDING ──▶ PAYMENT_TIMEOUT     ──▶ PAID | CANCELLED   (reconciliation decides)
```

Three properties are worth stating explicitly, because each one is a bug the table prevents:

**Every write consults the table first.** At-least-once delivery is a certainty, not a risk. A
payment callback arriving twice finds the order already `PAID`, and `PAID → PAID` is not a legal
edge — so the duplicate is rejected by the state machine rather than corrupting the order.

**A timeout is not a failure.** `PAYMENT_FAILED` means the provider said no, and releasing the stock
is safe. `PAYMENT_TIMEOUT` means the provider said nothing, and the charge may still land. Auto-
releasing there is how you sell the same unit twice and then have to refund one of them. It goes to
reconciliation, which can settle it either way — hence `PAYMENT_TIMEOUT` is the only state with two
legal exits.

**Compensation is a state, not a side effect.** `PAYMENT_FAILED` and `RESERVATION_EXPIRED` are real,
persisted states that funnel into `CANCELLED` only once the stock is actually back. An order is
never quietly cancelled with its reservation still held.

`OrderStateMachine.releasesInventory(state)` is the single predicate the compensation logic asks.

---

## Event flow

```
OrderCreated
     ↓
InventoryReserved
     ↓
PaymentRequested
     ↓
PaymentCompleted
     ↓
OrderConfirmed
     ↓
ShipmentCreated
```

Topics are per-aggregate, named `flashcart.<aggregate>.events` (`Topics` in `flashcart-common`), so
a new consumer can subscribe without the producer changing.

Three fields carry the design (`DomainEvent`):

- **`eventId`** makes consumers idempotent. Every consumer records the IDs it has applied, because
  at-least-once redelivery is guaranteed to happen, not merely possible.
- **`aggregateId`** is the partition key. Kafka orders messages *within a partition only*, so every
  event for one order must key on that order or the order service will see them out of sequence.
- **`correlationId`** carries the originating request's ID across the async hop — the only thing
  that keeps a checkout traceable once it stops being a single HTTP call.

Contracts are declared in Phase 1; producers and consumers arrive in Phase 5, and Phase 8 backs
publication with a transactional outbox so an event can never be published for a transaction that
rolled back.

---

## Cross-cutting concerns in `flashcart-common`

A shared library, not a shared service. It holds contracts and plumbing, never business logic.

| Piece | Why it is shared |
|---|---|
| `OrderStatus` / `OrderStateMachine` | The state names are on the wire; the rules must not fork |
| `DomainEvent` / `Topics` | A producer and its consumers cannot drift apart if the names live once |
| `ApiError` / `PageResponse` | One error and pagination shape across every service |
| `FlashCartException` hierarchy | Stable machine-readable `code`s, independent of HTTP status |
| `CorrelationIdFilter` | One request ID across seven services |

The servlet-side web plumbing is registered by **auto-configuration** guarded on
`@ConditionalOnWebApplication(SERVLET)`, not by component scanning. Each service scans only its own
package, and the reactive gateway depends on the same module and silently skips all of it rather
than failing on a missing `DispatcherServlet`.

---

## Catalog design notes (Phase 2)

### Flash-sale phase is derived, never stored

A sale row stores an admin's *intent* — `DRAFT`, `SCHEDULED`, `CANCELLED` — and nothing about
whether it is live. Live-ness is computed from the window on every read:

```
CANCELLED               → CANCELLED
DRAFT                   → DRAFT
now <  startsAt         → UPCOMING
startsAt ≤ now < endsAt → ACTIVE
now ≥ endsAt            → ENDED
```

The alternative is a stored flag flipped by a scheduler, and every second that scheduler is late is
a second the storefront sells at the wrong price. Deriving it also makes "is this live" a pure
function of the row and the clock, which is what will make it trivially cacheable in Phase 7.

The window is **half-open** (`startsAt` inclusive, `endsAt` exclusive) so two back-to-back sales on
the same product can never both be live for the instant they touch.

### Pricing resolves in one place

`PricingService` is the only thing that answers "what does this cost right now", and it batches:
one query prices a whole page. Pricing 50 rows one at a time is the N+1 that makes a listing slowest
exactly when a sale makes it busiest.

Responses carry a computed `effectivePrice` and `discountPercent`. Clients render those and never
recompute, so the grid and the checkout cannot disagree, and every surface shows the same `-40%`
badge instead of each rounding its own way.

### Concurrency, at catalog's modest scale

Catalog writes are rare and human-driven, so it uses **optimistic** locking: a JPA `@Version` on
`Product`, plus an explicit `expectedVersion` precondition so a stale editor gets a clear 409 up
front rather than at flush time. Uniqueness is defended by database constraints with the
`exists`-checks as a courtesy — two concurrent creates can both pass the check, and the
`DataIntegrityViolationException` is translated into a 409 rather than surfacing as a 500.

The genuinely contended concurrency problem is inventory's, and it is a different problem needing
different tools (atomic Redis operations, pessimistic locks, reservation expiry). That is Phases 3
and 7.
