# Load testing (Phase 10)

Four things were deferred to this phase by earlier ADRs: measure `ATOMIC_UPDATE` against
`PESSIMISTIC_LOCK`, measure the availability gate in the path against out of it, watch the outbox
relay under load, and give the two Phase 8 tables a retention policy.

Three of those produced a clear answer. One did not, and the honest reporting of that is the most
useful thing on this page.

## How to run it

```bash
./load/run.sh <ATOMIC_UPDATE|PESSIMISTIC_LOCK> <gate true|false> [stock] [vus] [attempts]
```

It restarts only inventory into the configuration under test, reads that configuration back out of
`/_info` and refuses to record a run it cannot label, seeds a fresh SKU, runs k6, and then checks the
books in PostgreSQL.

## What was measured

2000 virtual shoppers arriving at once for **100 units**, 200 VUs, through the gateway, on a laptop
running the whole eleven-container stack alongside the load generator.

## The result that matters

**Every configuration sold exactly 100 units.**

| Strategy | Gate | Granted | Refused | Errors | Shed | `reserved` in PostgreSQL |
|---|---|---|---|---|---|---|
| `ATOMIC_UPDATE` | on | 100 | 1900 | 0 | 0 | 100 |
| `ATOMIC_UPDATE` | off | 100 | 1900 | 0 | 0 | 100 |
| `PESSIMISTIC_LOCK` | on | 100 | 1900 | 0 | 0 | 100 |
| `PESSIMISTIC_LOCK` | off | 100 | 1900 | 0 | 0 | 100 |

2000 attempts, 100 units, no configuration overselling by a single unit, and every rejected buyer
getting a clean `409` rather than a timeout or a stack trace. That is
[ADR 0006](adr/0006-conditional-update-prevents-overselling.md) holding up under the only conditions
that could have disproved it.

## The result that does not exist

**The throughput comparison could not be made on this hardware.** Run-to-run variance inside a single
configuration is larger than every difference between configurations:

| Configuration | Repeat runs (req/s) |
|---|---|
| `ATOMIC_UPDATE`, gate off | 394, 460, **271** |
| `ATOMIC_UPDATE`, gate on | 319, 390, 419 |
| `PESSIMISTIC_LOCK`, gate off | 299 |
| `PESSIMISTIC_LOCK`, gate on | 372 |

One configuration swings from 271 to 460 req/s — a 70% spread — while the gap between the fastest and
slowest *configurations* is smaller than that. Any ranking drawn from these numbers would be a
ranking of which run happened to get a quieter machine.

So this page does not say the conditional `UPDATE` beats the pessimistic lock, and does not say the
gate improves throughput. **It says the experiment cannot tell, at this scale on this machine.**

That also means an earlier observation should be treated with more suspicion than it was given at the
time: Phase 7 noted `InventoryConcurrencyIT` dropping from ~58s to ~8.8s once the gate was added.
That was a test-suite runtime with containers starting and stopping inside it, not a controlled
benchmark, and it is not reproduced here. It should not be quoted as a performance figure.

Distinguishing these would need a machine that is not also running the system under test, longer
runs, and enough repetitions to put an interval around each number.

## What the load test found anyway

Two real defects, neither of them a performance problem.

**Load shedding reported itself as a fault.** Inventory runs a 30-connection pool with a deliberate
two-second `connection-timeout`, so that a stampede sheds load rather than queueing every buyer for
thirty seconds behind a connection that will not arrive in time to matter. Under 200 concurrent
buyers, 169 requests were waiting on a pool of 30 and 49 of 2000 timed out — the design working. But
each one surfaced as a **500**, which tells a client the service is broken and invites it to give up
or wake somebody. They are now **503 with `Retry-After: 1`**, which is both true and actionable.

**The relay kept up.** `flashcart_outbox_oldest_age_seconds` stayed at zero throughout, so the
concern recorded in [ADR 0017](adr/0017-outbox-and-processed-events.md) — that a polling relay might
fall behind under load and justify moving to change data capture — did not materialise at this scale.
Nothing to do, which is worth writing down so it is not re-litigated from memory.

## The harness lied three times before it told the truth

Worth recording, because the failure mode is not specific to k6.

The load generator reported **102**, then **425**, then **626 grants against 100 units** across
successive attempts. Every one of those numbers was wrong, and PostgreSQL held exactly 100 every
single time. The platform was never at fault.

The cause each time was a repeated `reservationKey`. The reserve endpoint is idempotent on that key —
retrying returns the original hold rather than taking a second one — so a reused key correctly comes
back `201`, and k6 counted each replay as a fresh sale.

The last of the three is the instructive one. `reservationKey` is idempotent **globally, not per
SKU**, and the keys restarted at `load-1-1` on every run. So each run's early requests matched holds
left behind by *previous* runs and were answered `201` for an entirely different product. The first
run of the day was accurate precisely because no earlier keys existed yet.

`run.sh` now **fails the run** when k6's grant count disagrees with `reserved` in the database. That
check is not a duplicate of the oversell check beside it: one asks whether the platform is correct,
the other asks whether the measurement can be believed at all. Without it, this page would have
published four confident throughput numbers derived from a tool that was counting replays as sales.

## Retention

`outbox_messages` and `processed_events` grow with traffic and were noted as debt in ADR 0017.
`OutboxRetention` now prunes both hourly, in batches.

The two tables are not equally safe to prune, and the difference is the whole point:

- A **published** outbox row has done its job; deleting it loses history and nothing else. Unpublished
  rows are never touched, because one of those is a message still owed to the bus.
- A **processed-events** row is the answer to "have I already handled this?". Deleting one does not
  free space so much as make that event processable again. The window must therefore exceed the
  broker's own retention: a message that can no longer be redelivered cannot be double-processed.

The default is seven days against Kafka's default seven — deliberately not a comfortable margin, and
documented on `OutboxRetention` so the next person changing Kafka's retention has a chance of noticing
that it is load-bearing.
