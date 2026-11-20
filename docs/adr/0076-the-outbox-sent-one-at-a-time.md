# 0076 — The outbox sent one at a time

**Status:** Accepted · **Date:** 2026-11-18 · **Phase:** post-roadmap · **Measures the checkout the way [ADR 0071](0071-everyone-signs-in-at-once.md) measured sign-in**

## Context

ADRs 0071 to 0074 load-tested sign-in and fixed what broke. Nothing had ever load-tested what a shopper does next:
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
- nobody turned away: no shopper failed to sign in, was refused at placing, or gave up waiting. Without this check, a
  run in which most shoppers never reached the shop reported its books in agreement, about the few who did.

Unlike `load/sign-in.sh`, it leaves what it made. Orders, payments and parcels are each service's records, with
history, outbox rows and emails behind them, and deleting them across five databases would be its own risk. All of it
is tagged with the run.

## What it found

Correct, and slow. With 1,000 shoppers for 100 units, and 3,000 for 300, the books agreed exactly: nothing was
oversold, nothing was dead-lettered, and every payment, parcel and reservation matched. But a shipped order took a
median of **102 seconds** to settle at 3,000 shoppers, and 226 of them gave up watching.

With **3,000 shoppers for 3,000 units**, so that every order goes the whole way, the saga fell minutes behind. When
the burst ended, 3,000 payments had completed but only 429 orders had shipped. 2,571 sat in `FULFILLING`, and the
order service's outbox held **7,514 unsent messages**. All of it was correct in the end: every order shipped, stock
went to 0 on hand and 0 reserved, and every outbox emptied. It took about ten minutes.

The outbox relay drained at 33–58 messages a second, whatever was waiting. The cause was in the relay itself:

- **One batch a tick.** At most 100 rows, then a one-second pause counted from when the batch finished, however many
  rows were waiting.
- **One message at a time.** `kafka.send(record).get()` for every row: each send waited for the broker's
  acknowledgement before the next was even handed to the producer. A batch of 100 was 100 round trips.

Every service with an outbox shares the relay, so this was a ceiling for the whole platform, not one service.

## Decision

### Send the batch, then wait for all of it

The whole batch is handed to the producer, and then each acknowledgement is awaited before its row is marked
published. The outbox's one promise holds: nothing is marked sent until the broker has it. Order within a key holds
too, because the relay's producer is idempotent with `acks=all`. Several requests in flight still land in the order
they were sent.

### Drain while behind

When a batch comes back full, there is more waiting, so the relay goes again at once instead of sleeping a tick. A
batch that comes back short ends it: the queue is empty, or a send failed, so failures still back off by a tick as
before. Draining is bounded by `flashcart.outbox.relay.drain-budget` (5 seconds), because every service's scheduled
jobs share one thread, and a relay that never yielded would hold up the rest. ADR 0046 found that thread being held
up once already.

### Say why a send failed

`last_error` now records the root cause, such as `RecordTooLargeException: The request included a message larger
than ...`. Before, it recorded Spring's wrapper, which says only "Failed to send". That column is what an operator
reads to find out why a saga has stopped.

## Verified

`OutboxRelayIT` drives the relay by hand against a real broker and a real table, with the timer parked and a batch of
ten:

- One tick drains a backlog of 35, more than three batches, and a key's 35 messages arrive in exactly the order they
  were queued.
- A message the producer refuses outright (an illegal topic name) stays queued, with its attempt counted. The rest of
  its batch is sent, and the short batch ends the drain.
- A message the broker rejects **after** it was sent (larger than its topic allows) stays queued, with the broker's
  reason recorded. None of the original tests covered this. A mutation that marked rows without waiting for the
  acknowledgement survived them all, because an illegal topic is refused before anything is in flight. This test
  was added because of that.

Five mutations, each now caught by the test written for it: one batch a tick again, draining past a short batch, a
refused message marked sent, rows marked before the broker answers, and the wrapper's message recorded. The fourth
survived until the broker-rejection test existed. The drain budget itself has no test: nothing here runs long enough
to reach five seconds.

`InventoryKafkaIT`'s round trips pass unchanged.

## Measured after

AFTER

## What this leaves open

- **One order in 30,000 requests was refused at placing.** At 1,000 shoppers the gateway answered 503 for a
  connection the order service dropped, probably a kept-alive connection Tomcat closed as the gateway reused it.
  It was retryable, and it did not recur in larger runs, but it was not chased down.
- **A shopper polls every three seconds.** At 3,000 shoppers that is a thousand reads a second on the order service,
  which is part of the load being measured. A real client might wait longer, or be pushed the change instead.
