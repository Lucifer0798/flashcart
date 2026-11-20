# 0076 — Everyone checks out at once

**Status:** Accepted · **Date:** 2026-11-20 · **Phase:** post-roadmap · **Measures the checkout the way [ADR 0071](0071-everyone-signs-in-at-once.md) measured sign-in**

## Context

ADRs 0071 to 0075 load-tested sign-in and fixed what broke. Nothing had ever load-tested what a shopper does next:
place an order, and let the saga reserve the stock, take the payment and book the parcel across four services over
Kafka. The flash-sale harness reserves stock directly and never goes near an order.

`load/checkout.sh [shoppers] [stock]` now does. It seeds week-old shoppers and one product priced to be approved (not
`.13` or `.99`, which the simulated provider declines or times out), with the stock under test. Every shopper signs
in, then all of them place an order for one unit at the same moment, and each watches their order every three
seconds until it settles: `SHIPPED`, or `CANCELLED` when the stock ran out. A shopper still watching after two minutes
gives up.

Then every service's books are read and held against each other, and the run fails if any of these break:

- nothing oversold: shipped ≤ stock, and every unit sells while there are buyers;
- nothing stuck: no order left part-way through the saga;
- the services agree: completed payments, parcels and committed reservations each equal the orders shipped;
- the stock adds up: `on_hand` is stock minus shipped, and `reserved` is zero;
- nothing lost or stuck in transit: no dead letters added, and no outbox row unsent;
- the shoppers were told the truth: what each one saw is what the order service holds;
- nobody turned away: no shopper failed to sign in, was refused at placing, or gave up waiting. That last check was
  added after a run in which most shoppers never reached the shop reported its books in agreement, about the few
  who did.

Unlike `load/sign-in.sh`, it leaves what it made. Orders, payments and parcels are each service's records, with
history, outbox rows and emails behind them, and deleting them across five databases would be its own risk. All of it
is tagged with the run.

## What it found

Three things, each hidden behind the one before.

### 1. The outbox sent one message at a time

The books were always right. Nothing was oversold or dead-lettered, and every payment, parcel and reservation matched.
But the saga was slow. At 3,000 shoppers for 300 units, a shipped order took a median of **102 seconds**, and 226
shoppers gave up watching. At **3,000 for 3,000**, so that every order goes the whole way, it fell minutes behind. When
the burst ended, 3,000 payments had completed but only 429 orders had shipped, and the order service's outbox held
**7,514 unsent messages**. Every order shipped in the end, but it took five to ten minutes.

The relay that every service shares had two limits:

- **One batch a tick.** At most 100 rows, then a one-second pause counted from when the batch finished, however many
  rows were waiting.
- **One message at a time.** `kafka.send(record).get()` for every row, so each send waited for the broker's
  acknowledgement before the next was even handed to the producer. A batch of 100 was 100 round trips.

Together they capped every service at 33–58 messages a second.

### 2. The gateway sent requests down connections Tomcat was closing

With the relay fixed, some runs refused 10–48 orders at placing. Every refusal arrived within one second, at the
instant the burst began, as a connection reset by the order service.

Each service's Tomcat closes a kept-alive connection after 60 idle seconds. The gateway's pool set no idle limit, so it
kept such connections and reused them. A burst that arrived just as Tomcat was closing a pool of idle connections was
sent down them as they closed. `load/idle-race.sh` shows it directly. It fills the gateway's pool to the order service
with 200 requests, leaves them idle, and sends 200 more. After 50, 59, 60 or 70 idle seconds nothing failed. After 61
seconds, **110 and 132 of 200** failed, in two of three tries; the third missed the window. Which runs it hit depended
only on how long the burst came after the warm-up's last request.

### 3. The services' accept queues were smaller than the gateway's pool

With the gateway retiring idle connections, a burst after any quiet spell starts with an empty pool, and opens up to
500 connections to a service at once (ADR 0074). Tomcat's accept queue holds 100 by default. A later run refused 44
orders, and the order container's kernel counted **1,157** accept-queue overflows. ADR 0074 had measured no overflow
with the bounded pool, because its pool stayed warm from one burst to the next.

## Decision

### The relay sends a batch at once, and drains while behind

The whole batch is handed to the producer, and then each acknowledgement is awaited before its row is marked
published. The outbox's one promise holds: nothing is marked sent until the broker has it. Order within a key holds
too, because the relay's producer is idempotent with `acks=all`, so several requests in flight still land in the order
they were sent.

When a batch comes back full, there is more waiting, so the relay goes again at once instead of sleeping a tick. A
batch that comes back short ends it: the queue is empty, or a send failed, so failures still back off by a tick as
before. Draining is bounded by `flashcart.outbox.relay.drain-budget` (5 seconds), because every service's scheduled
jobs share one thread, and ADR 0046 found that thread being held up once already.

`last_error` now records a failed send's root cause, such as `RecordTooLargeException: ...`. Before, it recorded
Spring's wrapper, which says only "Failed to send". That column is what an operator reads to find out why a saga has
stopped.

### The gateway retires idle connections first

`httpclient.pool.max-idle-time: 30000`: the gateway closes a connection after 30 idle seconds, before any service's
Tomcat would at 60. A connection the gateway closes itself cannot be closed under a request.

### Every service's accept queue holds the gateway's whole pool

`server.tomcat.accept-count: 1000` on all six services. That is above the gateway's 500 per service, and below the
containers' `somaxconn` of 4096. The two settings live in different files, so `scripts/connection-limits-check.py`
fails the build when a service's queue is smaller than the gateway's pool, or not set at all.

## Verified

`OutboxRelayIT` drives the relay by hand against a real broker and a real table, with the timer parked and a batch of
ten:

- One tick drains a backlog of 35, more than three batches, and a key's 35 messages arrive in exactly the order they
  were queued.
- A message the producer refuses outright (an illegal topic name) stays queued, with its attempt counted. The rest of
  its batch is sent, and the short batch ends the drain.
- A message the broker rejects **after** it was sent (larger than its topic allows) stays queued, with the broker's
  reason recorded. A mutation that marked rows without waiting for the acknowledgement survived every other test,
  because an illegal topic is refused before anything is in flight. This test was added because of that.

Five relay mutations, each now caught by the test written for it. They were one batch a tick again, draining past a
short batch, a refused message marked sent, rows marked before the broker answers, and the wrapper's message recorded.
The drain budget itself has no test: nothing here runs long enough to reach five seconds.

`GatewayRoutingTest` asserts the 30-second idle limit beside ADR 0074's pool settings, and fails without it.
`connection-limits-check.py` was seen to fail with one service at 100 and with one unset.

## Measured

`main`'s relay and this one, alternated in one session on the same machine. Every run waited first for every outbox to
be empty and no order in flight, and was checked for any container restarting mid-run. None did.

| 3,000 shoppers for 3,000 units | `main` | this change |
|---|---|---|
| every order settled and every outbox empty | 342 s, 321 s | 133 s, 135 s |
| shoppers who saw SHIPPED within two minutes | 48, 159 | every one who placed |
| median to SHIPPED | 107 s, 106 s | 40 s, 43 s |
| order outbox when the burst ended | 8,151, 6,950 | 0, 0 |

Those relay runs refused 38 and 10 orders at placing, and that led to the idle race and the accept queue. With all
three changes in place, three final runs refused nobody:

| | 3,000 for 3,000 | 3,000 for 3,000 | 3,000 for 300 |
|---|---|---|---|
| couldn't sign in, refused at placing, gave up | 0, 0, 0 | 0, 0, 0 | 0, 0, 0 |
| accept-queue overflows, order and user | 0, 0 | 0, 0 | 0, 0 |
| median to SHIPPED | 52 s | 26 s | **9.8 s** (102 s before) |
| every order settled and every outbox empty | 153 s | 112 s | 85 s |
| books | agree | agree | agree |

Per ADR 0019 none of these is a throughput figure for anything but this machine. The comparison alternated, and its
difference is several times the spread within either side.

## What this leaves open

- **A shopper polls every three seconds.** At 3,000 shoppers that is a thousand reads a second on the order service,
  which is part of the load being measured. A real client might wait longer, or be pushed the change instead.
- **Building images in parallel crashed Docker Desktop.** Six Maven builds at once inside its 8 GB VM took the daemon
  down. The comparison scripts build one image at a time, but `docker compose build` with no service named still
  builds them all at once.
