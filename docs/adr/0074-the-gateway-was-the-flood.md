# 0074 — The gateway was the flood

**Status:** Accepted · **Date:** 2026-11-17 · **Phase:** post-roadmap · **Answers an open item of [ADR 0073](0073-turned-away-is-a-fact.md)**

## Context

After password checks were capped (ADR 0071), every shopper turned away at a sale's opening came the same way. The user
service dropped connections, and the gateway answered 503 for it (ADR 0073). Nothing said which limit was closing them.
The candidates were Tomcat's 200 request threads, its accept queue of 100, and its connection timeout.

## How it was found

Measured rather than guessed. `load/sign-in.sh` now records how long each refused shopper waited before the 503. A
probe snapshotted the kernel's TCP counters inside the user and gateway containers before and after a 4,000-shopper
burst.

| Counter | Moved by |
|---|---|
| user: `ListenOverflows`, `ListenDrops` | +13,434 each |
| user: `OutRsts` | +516 |
| gateway: `TCPSynRetrans` | +11,337 |
| gateway: `EstabResets` | +516 |
| k6: shoppers refused | 516 |

The user service's accept queue was overflowing. Its resets matched the refusals exactly, and the refused had waited
17–30 seconds, which is the kernel's handshake retries running out. No application timeout was involved.

The first explanation was CPU starvation of Tomcat's acceptor thread, the same mechanism as ADR 0071 one layer down.
A test refuted it. Leaving cores free, with 12 password slots instead of 16, still refused 273 and 819 shoppers. A
deeper accept queue, `accept-count` 1000, refused nobody, but the queue still overflowed by up to 3,208. That pointed
upstream: something was opening far more connections than any queue should have to hold.

It was the gateway. Spring Cloud Gateway's HTTP client pool defaults to **elastic**: a new connection for every request
in flight, with no limit. Counted from inside the gateway during the burst, it held **3,594 established connections to
the user service at once, with 1,330 more being opened**. That is a service with 200 request threads and an accept
queue of 100. The gateway turned 4,000 waiting shoppers into 4,000 connection attempts and left the kernel to queue
them, and the kernel's queue is the one place where overflow becomes a reset after half a minute.

## Decision

### A fixed pool per service

```yaml
spring.cloud.gateway.server.webflux.httpclient.pool:
  type: FIXED
  max-connections: 500    # more than any service's 200 request threads
  acquire-timeout: 45000  # Reactor Netty's default, written down
  metrics: true
```

At most 500 connections to each service. A burst larger than that waits at the gateway, in order. There it is
measured: `reactor_netty_connection_provider_pending_connections`, by remote address, is on the dashboard. Nothing
waits in a kernel queue where overflow is silent. 500 is above every service's thread count, so the gateway never
starves a service of work. The deeper accept queue was also tried with the pool, and added nothing, so the user
service is unchanged.

### The pool's own refusal is a 503

A request that waits past the acquire timeout, or finds the pending queue full, fails with Reactor Netty's
`PoolAcquireTimeoutException` or `PoolAcquirePendingLimitException`. `UpstreamFailureHandler` now answers those with
503 and `Retry-After` too, and counts them under `UpstreamUnavailable`. Before this, they would have reached the
default handler as 500s. They are matched by simple class name, because Reactor Netty keeps them in a shaded internal
package that an upgrade is free to move. A `TimeoutException` of any other name still goes to the default handler.

`GatewayRoutingTest` asserts the pool is FIXED with 500 connections, the acquire timeout and metrics on. So the setting
cannot quietly go back to elastic, which is the default and what an accidental deletion would restore.

## Measured

All at 4,000 shoppers on the same machine and day unless stated, two runs each, after a warm-up:

| Configuration | Refused | Accept-queue overflows | Slowest sign-in |
|---|---|---|---|
| elastic (before) | 283, 212 | 5,913, 5,106 | 31 s, 30 s |
| elastic, 12 password slots | 273, 819 | 3,959, 6,343 | 35 s, 28 s |
| elastic, accept-count 1000 | 0, 0 | 688, 3,208 | 37 s, 33 s |
| **fixed 500 (this change)** | **0, 0** | **none** | 32 s, 32 s |
| fixed 500, accept-count 1000 | 0, 0 | none | 41 s, 40 s |

With the fixed pool, no TCP counter in the user container moved at all, and the gateway held exactly 500 connections.

**6,000 shoppers** then all got in, the slowest after 33 s. That is the same wait as 4,000, where before 4,000 had lost
212–516. Turning thousands of connection attempts into retries had been costing throughput as well as shoppers. Per
ADR 0019 that is not a throughput figure. It is the same machine, the same day and the same harness, and the
difference is far outside the spread.

**The pool's refusal, live:** with the acquire timeout lowered to 5 seconds for one run, 2,181 sign-ins and 248
refreshes were refused after 6–7 seconds. Every one was a 503 with `Retry-After` and none was a 500. Prometheus counted
them for `user`, `UpstreamUnavailable` fired, and the books agreed. The timeout was then put back.

Mutations: the pool set back to elastic, the pool refusals not recognised, and every `TimeoutException` treated as
one. Each was caught by the test written for it.

## Consequences

- At a sale's opening, a burst larger than a service can take waits at the gateway, visibly, instead of being reset
  by the kernel after half a minute.
- One shopper can now wait up to 45 seconds at the gateway before a 503. Before, the wait was 10–30 seconds before a
  reset that also became a 503. The wait is longer, but it ends in a sign-in far more often.
- A slow service now holds at most 500 of the gateway's connections, not all of them.

## What this leaves open

- **Other services were not measured under this change.** The flash-sale harness reserves with 200 virtual users, well
  under 500, and its CI run and the full build pass. No order or inventory burst larger than the pool was tried.
- **45 seconds is Reactor Netty's number, not one chosen for shoppers.** It is written down here so that it can be
  argued with, rather than inherited unseen.
