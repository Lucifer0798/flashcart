# 0075 — The gateway remembered the old address

**Status:** Accepted · **Date:** 2026-11-18 · **Phase:** post-roadmap · **Leaves [ADR 0074](0074-the-gateway-was-the-flood.md)'s pool as it was**

## Context

On 2026-11-18 Kafka died on a Docker DNS fault. The user service crash-looped, restarted 48 times, and came back on a
new container address, 172.20.0.5. The gateway had run throughout. Every request to `/api/v1/users` was answered 503
by `UpstreamFailureHandler`:

```
AnnotatedConnectException: finishConnect(..) failed with error(-111): Connection refused: user/172.20.0.7:8083
```

Inside the gateway's container, `getent hosts user` already answered 172.20.0.5. The operating system knew the new
address and the gateway did not. `docker compose restart gateway` fixed it at once.

## How it was found

**The resolver the gateway uses.** Spring Cloud Gateway 5.0.3's `HttpClientFactory` builds the routing `HttpClient`
and never sets a resolver, so Reactor Netty 1.3.7's default applies. That default is Netty's own asynchronous
`DnsNameResolver`, not the JDK's, which is why `getent` disagreed with it. Its `NameResolverProvider` defaults, read
from the bytecode, are a maximum cache time of `Integer.MAX_VALUE` seconds, a minimum of 0 and a negative cache of 0.
An answer is kept for exactly as long as its TTL says.

**The TTL it is given.** A raw DNS query to Docker's embedded server (127.0.0.11, the only name server in the
gateway's `resolv.conf`) answered `user` with **TTL 600**. So once the gateway has looked a service up, it keeps that
address for up to ten minutes, however often the service moves.

**Reproduced on the compose stack.** The gateway was restarted so that its first lookup happened at a known moment. A
probe inside the compose network sent `GET /api/v1/users/me` through the gateway every 0.25 s. 401 means the user
service answered and 503 means the gateway answered for it. The user service was stopped, another container was
started on its old address (172.20.0.5, so that connecting to it is refused at once, as in the incident), and the user
service was recreated. It came back on 172.20.0.18.

The new container was healthy 41.8 s into the run, and `user:8083` answered it directly from another container. The
gateway went on answering 503 for **549.4 s after that, 2,146 requests**, every one
`Connection refused: user/172.20.0.5:8083`. It recovered on its own 600.7 s after its lookup, which is the TTL,
with nobody touching it. That matches the incident exactly, including how `getent` and the gateway disagreed.

## Decision

### Keep an address for five seconds at most

```java
@Bean
HttpClientCustomizer upstreamDnsCacheCap(
        @Value("${flashcart.gateway.dns.cache-max-time-to-live:5s}") Duration cacheMaxTimeToLive) {
    return client -> client.resolver(spec -> spec.cacheMaxTimeToLive(cacheMaxTimeToLive));
}
```

`HttpClientFactory` applies every `HttpClientCustomizer` bean to the client that carries every route. The cap is the
only thing that changes. The resolver is still Netty's and still asynchronous, the negative cache stays at zero, and
the pool from ADR 0074 is untouched. Connections already in the pool are reused as before. A lookup happens only when
a new connection is opened and the cached answer is more than five seconds old, and it goes to a DNS server on the
same host. `GATEWAY_DNS_CACHE_MAX_TTL` overrides it.

Five seconds is shorter than the time a restarted service takes to start listening (about 20 s here), so the gateway
has the new address before the service can answer on it. It is also long enough that a burst opening hundreds of
connections at once (ADR 0074) mostly finds the answer cached. The sign-in runs below show no difference from the
default.

### Not the JDK's resolver

The alternative was `DefaultAddressResolverGroup`, the JDK's resolver, with a short `networkaddress.cache.ttl` (its
default without a security manager is already 30 s). It was rejected on a measurement. While a container is stopped,
Docker's DNS no longer knows its name, and a `getaddrinfo` for it from inside the compose network took **3.9 s** to
fail, twice in a row. A name Docker never knew behaves the same, timing out a raw query after 3 s. Netty calls the
JDK resolver on the event loop, so for those seconds every route sharing that loop would wait too. Netty's resolver
waits for the same answer without blocking anything.

### Tested

`GatewayRoutingTest` reads the resolver back from the `HttpClient` bean that `NettyRoutingFilter` uses, and asserts a
five-second cap and no negative caching. Two mutations were each caught: the customizer returning the client
unchanged (no resolver configured), and the property's default set back to 600 s (`expected: 5S but was: 10M`).

## Measured

The same reproduction with the cap: the gateway restarted and looked `user` up, another container took the user
service's address (172.20.0.18), and the user service was recreated on 172.20.0.19.

| | Before | After |
|---|---|---|
| Refused at the old address after the move | until the TTL ran out | 23 requests, about 6 s |
| 503s after the new container was healthy | **2,146 over 549.4 s** | **none** |
| First answer from the new container | 549.4 s after healthy | 1.1 s *before* healthy |

After the cap ran out, the gateway connected to the new address at once and was refused only until the user service
was listening. It answered the first request the moment Tomcat did, ahead of Docker's health check.

A second move happened unplanned during the measurements, when another session recreated the user service for its
own run. The address changed from .19 to .3, and the gateway followed within the same ten seconds.

**While the service is gone, it is still a 503.** With the user service stopped for 40 s, its name no longer
resolved. Each request through the gateway waited about 3.85 s for the lookup to fail, then got
`503`, `Retry-After: 1` and `UPSTREAM_UNAVAILABLE`, as before. `UnknownHostException` is an `IOException`, which
`UpstreamFailureHandler` already treats as an upstream failure. No 500s and no errors were logged. The first request
after the start was answered 0.1 s after the health check passed. One request, sent just as the container stopped,
was still waiting when the probe gave up after 10 s. The likely cause is a connection attempt to an address that had
just gone away, inside the five seconds, but that was not confirmed. Before this change, every request in that
position would have done the same until the TTL ran out.

**ADR 0074 is unchanged.** `GatewayRoutingTest` still asserts the fixed pool. After every sign-in burst, the gateway
reported `max_connections` 500 and `total_connections` 500 to `user:8083`.

**`./load/sign-in.sh 4000 0`**, on one image, alternating the cap. Each run came after a gateway restart, and runs
2–5 also after a 500-shopper warm-up. `GATEWAY_DNS_CACHE_MAX_TTL=2147483647s` gives a resolver equal to Reactor Netty's default,
so the "main" arm resolves exactly as `main` does.

| Run | Cap | Signed in | Shed (503) | Slowest sign-in | Stack |
|---|---|---|---|---|---|
| 1 | 5 s | 4,000 of 4,000 | 0 | 50 s | to itself |
| 2 | default | 4,000 of 4,000 | 0 | 52 s | to itself |
| 3 | 5 s | 3,513 | 487 | 59 s | another session's 3,000-shopper checkout run at the same time |
| 4 | default | 3,499 | 485 | 60 s | the same |
| 5 | 5 s | 4,000 of 4,000 | 0 | 46 s | to itself |

Every run the stack had to itself refused nobody, with either setting. The two runs that overlapped another session's
load test shed the same amount with either setting, so the cap played no part. The slowest sign-ins are longer than
ADR 0074's 32 s on both arms alike: this is a different day on the same machine (ADR 0019).

## Consequences

- A service that comes back on a new address is reachable through the gateway again within seconds, not after up to
  ten minutes, and nobody has to restart the gateway.
- The gateway asks Docker's DNS for a service's address again when it opens a new connection and its answer is more
  than five seconds old. Connections it already holds are not affected.
- Behind a Kubernetes Service, whose ClusterIP does not change while the Service exists, the cap changes nothing.

## What this leaves open

- **Other clients in the platform were not checked.** The order service's catalog client builds its request
  factory with `ClientHttpRequestFactoryBuilder.detect()`, so how it caches addresses depends on which HTTP client is
  on its classpath. It does not go through this resolver. Whether it can hold a stale address, and for how long, was
  not measured.
