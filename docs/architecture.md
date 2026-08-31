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

### Concurrency, at catalog's modest scale (Phase 2)

Catalog writes are rare and human-driven, so it uses **optimistic** locking: a JPA `@Version` on
`Product`, plus an explicit `expectedVersion` precondition so a stale editor gets a clear 409 up
front rather than at flush time. Uniqueness is defended by database constraints with the
`exists`-checks as a courtesy — two concurrent creates can both pass the check, and the
`DataIntegrityViolationException` is translated into a 409 rather than surfacing as a 500.

The genuinely contended concurrency problem is inventory's, and it is a different problem needing
different tools (atomic Redis operations, pessimistic locks, reservation expiry). That is Phases 3
and 7.

---

## Inventory design notes (Phase 3)

This is the service the platform is really about. Everything else can be slow, or wrong for a
moment, and be forgiven. This one cannot promise a unit it does not have.

### The one statement that does the work

```sql
update stock_items
   set reserved = reserved + :quantity
 where sku = :sku
   and on_hand - reserved >= :quantity
```

The check and the write are the same statement, so there is no window between them for a concurrent
buyer to slip through. Zero rows affected means someone else won the unit — during a sale, the
ordinary outcome rather than an error. Full reasoning, and why optimistic and pessimistic locking
were both rejected for this path, in [ADR 0006](adr/0006-conditional-update-prevents-overselling.md).

Behind it, `CHECK (reserved <= on_hand)` in the schema. If the application logic were ever wrong,
PostgreSQL refuses rather than sells the unit twice.

### Three checks, not one

A flash-sale reservation must satisfy all three of:

| Condition | Enforced by | Failure code |
|---|---|---|
| The warehouse has the units | `stock_items` | `INSUFFICIENT_STOCK` |
| The sale has not sold its allocation | `sale_allocations` | `SALE_ALLOCATION_EXHAUSTED` |
| The customer is under their cap | `customer_sale_limits` | `CUSTOMER_LIMIT_EXCEEDED` |

Three separate atomic counters, checked in addition to each other, never instead. A warehouse holding
5,000 units can still run a sale permitted to move only 500 — see
[ADR 0008](adr/0008-atomic-counters-for-caps-and-allocations.md).

All three read as "sold out" to a shopper and are entirely different operationally, which is why
they are distinct codes rather than one generic 409.

### Reservations and the two expiry paths

```
reserve ──▶ HELD ──┬──▶ COMMITTED   payment landed; units leave the warehouse
                   ├──▶ RELEASED    abandoned, declined, cancelled
                   └──▶ EXPIRED     the timer won
```

A hold lets a customer spend ninety seconds on a card form without anything holding a database lock
across that. Expiry runs two ways, and both are needed:

- **Lazily**, on the reserve path, so a buyer is never told "sold out" because a background job had
  not yet reclaimed units that lapsed thirty seconds ago.
- **On a schedule**, for SKUs nobody is asking about any more — otherwise a quiet product's expired
  holds leak forever.

[ADR 0007](adr/0007-reservations-with-two-path-expiry.md) has the reasoning, including the
connection-pool deadlock that the obvious implementation caused.

Committing an expired hold is refused with a 409. Those units are back in the pool and may already
be someone else's, so the caller must reconcile — which is exactly the `PAYMENT_TIMEOUT` →
reconciliation edge the order state machine already has.

### Deadlock avoidance in multi-line reservations

Two reservations touching SKUs `A` and `B` in opposite orders deadlock: each holds the row the other
wants. Every reservation therefore sorts its lines by SKU before touching anything, so all callers
take rows in the same global order. It costs one sort per request and is tested explicitly
(`ReservationLinesTest`), because it looks like tidiness and is not.

### What the ledger is for

`stock_items` holds the balance; `stock_movements` holds how it got there — signed deltas that
replay to the balance, each carrying the correlation id of the request that caused it. Without it,
"we are three units short" is unanswerable. `RELEASED` and `EXPIRED` are deliberately distinct: who
gave up and who ran out of time are different operational questions.

### Why there is no Redis here yet

Phase 7 adds it. Building the durable, provably-correct Postgres implementation first means Redis
arrives as an optimisation in front of a system of record that already works, and can be measured
against it — rather than becoming the system of record for the one number the platform cannot afford
to lose. The `strategy` setting (`ATOMIC_UPDATE` / `PESSIMISTIC_LOCK`) exists for the same reason:
Phase 10 measures the difference instead of asserting it.

### What proves any of this

`InventoryConcurrencyIT` fires sixty simultaneous requests, released together off a latch, at scarce
stock, and asserts that **exactly** the available number succeed. One too many is an oversell; one
too few is a lost sale. The same test exists for the allocation and for the per-customer cap, and one
for sixty concurrent retries of a single order key yielding exactly one hold.

Nothing in a single-threaded suite can catch the bug this service is built to prevent, because that
bug only exists when requests overlap.

---

## Order design notes (Phase 4)

The order is the aggregate everything else turns around. This is where the state machine declared in
Phase 1 stops being a contract and starts being enforced.

### A checkout, and every way it can fail

```
price from catalog  ─▶ CREATED ─▶ hold in inventory ─▶ RESERVED ─▶ PAYMENT_PENDING
                          │                              │              │
        catalog 404 ──────┘                              │              ├─▶ PAYMENT_FAILED ─▶ CANCELLED
        (nothing persisted)                              │              └─▶ PAYMENT_TIMEOUT ─▶ reconcile
                                                         │
                       inventory refused ────────────────┼─▶ CANCELLED  (reason recorded)
                       inventory silent  ────────────────┼─▶ stays CREATED, retryable
                       hold lapsed       ────────────────┴─▶ RESERVATION_EXPIRED ─▶ CANCELLED
```

Nothing is persisted until the basket has priced, so a bad SKU leaves no wreckage. Everything after
that has a defined destination — the point of the state machine is that there is nowhere for an
order to end up that nobody planned for.

### The state machine is enforced by the aggregate, not by callers

`Order.transitionTo` is the only path to a status change, and it calls
`OrderStateMachine.assertTransition` first. That single choice is what makes the platform safe
against the thing distributed systems guarantee: a duplicate delivery. A payment callback arriving
twice finds the order already `PAID`, and `PAID → PAID` is not an edge. An expiry timer firing after
the charge settled is refused the same way. No caller has to remember.

It also caught a real bug during Phase 4. `cancel()` released the stock and *then* transitioned —
so cancelling an order with a payment in flight would have freed the units and only then discovered
that `PAYMENT_PENDING → CANCELLED` is not a legal move, leaving stock released and the order still
live. The guard now runs before the release.

### Three answers from inventory, not two

Refused and unanswered are different failures and are handled differently — see
[ADR 0010](adr/0010-refusal-and-silence-are-different-failures.md). The short version: cancelling on
a timeout can strand real stock, and confirming on one can promise stock nobody holds, so an order
whose reserve went unanswered stays `CREATED` and is resumed by a retry.

That resume is load-bearing and was itself a bug found by test: `place()` originally returned any
existing order for an idempotency key, including a stranded `CREATED` one, which made the retry
useless.

### No transaction crosses the network

`OrderService.place` is deliberately not `@Transactional`. Wrapping the checkout in one transaction
would hold a database connection across two HTTP calls — so a slow inventory drains the pool — and
would be a lie anyway, since a local rollback cannot un-reserve remote stock. Transactions are small
and local; compensation is written out. [ADR 0009](adr/0009-no-transaction-across-a-network-call.md).

### What the history table is for

Every transition is persisted with its reason and the correlation id of the request that caused it.
`orders.status` says where an order is; `order_status_history` says how it got there. "Why is this
order cancelled" should not be answerable only from application logs that may have rotated away.

Note that compensation states are recorded rather than skipped: a declined payment walks
`PAYMENT_PENDING → PAYMENT_FAILED → CANCELLED`, not straight to `CANCELLED`. The intermediate state
is the explanation.

### Why the reservation key is the order id

Inventory is idempotent on the reservation key. Making that key the order id means a retried or
timed-out reserve is safe to repeat — it either creates the hold or returns the one the lost call
already made. That property is what lets the unavailable case be "retry" rather than "guess", and it
was designed into Phase 3 for exactly this.

### What Phase 5 changes

The `InventoryClient` interface is the seam. Today `RestInventoryClient` makes a synchronous call;
Phase 5 replaces it with a published command and an event reply, and `OrderService` should not need
to change. The synchronous coupling is temporary and deliberately visible rather than hidden.
