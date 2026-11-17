# 0073 — Turned away is a fact

**Status:** Accepted · **Date:** 2026-11-16 · **Phase:** post-roadmap · **Answers an open item of [ADR 0071](0071-everyone-signs-in-at-once.md)**

## Context

ADR 0071 capped password checks at the number of cores, so a sale's worth of sign-ins queues for the CPU instead of
starving the connection pool. Nothing measured that queue. A sign-in that is slow because it waited for a core looks,
from outside, exactly like one that is slow because the database is, and the two have opposite fixes. Nothing
measured customers being turned away either. The load harness counted refusals from the outside, but a production
dashboard had no way to see them.

## Decision

### The queue is measured, for diagnosis, not for alerting

`BoundedPasswordEncoder` registers four meters when it is built, so a dashboard shows zero rather than no data:

| Meter | What it says |
|---|---|
| `flashcart_user_password_checks_waiting` | checks queued for a slot now |
| `flashcart_user_password_checks_in_progress` | checks hashing now, never more than the slots |
| `flashcart_user_password_checks_slots` | the cap itself |
| `flashcart_user_password_checks_wait_seconds` | a histogram of each check's wait, including the ones that waited for nothing |

Every check is timed, not only the ones that waited. A histogram of waits alone would make a slow sign-in look typical.

None of these alerts. `rules.yml` alerts only on states that are wrong on their own terms, and never on a metric
merely being high. A queue when a sale opens is the cap doing its job. Its length depends on how many people arrive
at once, which is not something to page anyone about.

### Turning a customer away is wrong on its own terms, and there are two ways it happens

The live check of this change found the second way. A first version alerted only on the connection pool, which
Hikari already counts (`hikaricp_connections_timeout_total`, for every service). During a 3,000-shopper burst, k6
counted 114 sign-ins refused, while the pool count stayed at zero and the alert stayed silent. Every one of those
refusals came from the gateway. The user service had dropped connections it had queued too long, and the gateway's
handler from ADR 0071 answered 503 for it. Capping the hashes moved where sale-time refusals happen: in ADR 0071's
runs before the cap, they came from the pool.

So the gateway's handler now counts each one in `flashcart_gateway_upstream_unavailable_total{upstream}`, by route.
Every route's counter is registered at zero on startup, plus `unrouted` for a failure with no route on the exchange.
A counter created on its first event is invisible to `increase()` ([ADR 0061](0061-the-first-theft-is-the-one-that-counts.md)).
The tag is `upstream`, not `service`, because Prometheus labels everything the gateway exports `service="gateway"`.

Two alerts, both warnings, both firing on any refusal at all. Each refusal is a customer told to come back, so there
is no threshold to justify:

- **`RequestsShedForConnections`**: a service's pool had nothing free for two seconds, and it answered 503 itself.
  Inventory sheds this way by design under a stampede, and that is still worth seeing.
- **`UpstreamUnavailable`**: the gateway answered 503 because the service refused or dropped the connection. That is
  load when the service is up, or a stopped service alongside `ServiceDown`.

The Grafana dashboard gains a row, "Sign-in at sale start — waiting for a core, or for a connection?". It shows the
queue, the wait's median and p95, refusals by service and by path, and requests waiting for a database connection.

## Verified

- **Unit tests.** The encoder's meters exist at zero before the first check. With two slots and five checks, they
  read two hashing and three waiting. All five checks are timed, and the queued ones waited at least as long as the
  first two were held. The gateway's counter is registered at zero per route, counts by route, counts a failure with
  no route as `unrouted`, and does not count errors it passes on.
- **promtool.** Each alert fires and names its service. It stays quiet for a busy pool that never timed out and for a
  counter reset by a restart, and it stops once the refusals are five minutes old. Five mutations of the two
  expressions were each caught: a raised threshold, the grouping dropped from either alert, and the raw counter used
  in place of its increase in either. The raw counter survived the gateway alert's first tests, because none had
  refusals that stopped. A case for that was added.
- **Live.** Every route's counter appeared in Prometheus at zero before any traffic. In a 4,000-shopper burst, k6
  counted 27 refusals, Prometheus measured 27.2 for `upstream="user"`, and `UpstreamUnavailable` fired for `user`.
  With the user service stopped, one sign-in answered 503 and the count rose by one. A 3,000-shopper burst in which
  nobody was refused fired nothing. Up to 183 checks were seen queued for 16 slots, with a median wait of 1.4 s and a
  p95 of 2.2 s.

The pool alert was not seen to fire live. No run in this session timed a connection out once hashes were capped. It
rests on its promtool tests, and on Prometheus carrying the counter for all six services with the `service` label the
rule groups by.

## Consequences

- Slow sign-in at a sale can be read from the dashboard as the CPU or the database.
- Every customer turned away with a 503, by either path, now raises a warning naming the service.

## What this leaves open

- **Why the user service drops connections rather than answering.** It does so under a burst, and the gateway now
  counts it, but which limit closes them was not established. Tomcat's thread pool and accept queue, and its
  connection timeout, are the candidates.
  *It was none of them: the gateway's unbounded connection pool flooded the user service's accept queue. See
  [ADR 0074](0074-the-gateway-was-the-flood.md).*
- **The pool alert is unseen in production conditions.** It is tested, but this session never produced a pool
  timeout after the cap.
