# 0070 — A test run is not a customer

**Status:** Accepted · **Date:** 2026-11-10 · **Phase:** post-roadmap · **Answers an open item of [ADR 0069](0069-the-order-services-log-was-kept-for-ever.md)**

## Context

Every service's `application.yml` defaults to the compose stack's host ports: Kafka on `localhost:19092`, PostgreSQL
on `localhost:15432`. That is right for running one service by hand next to the stack. Tests inherit the same
defaults, and nothing stopped them using them.

ADR 0069 found one case: a first run of the order service's `AuditRetentionIT` joined a running stack's consumer
groups and caused a rebalance. It also noted that `spring.kafka.listener.auto-startup=false`, which several suites
set for exactly this reason, does nothing. That made it look like one suite and one comment. It was more than that.

With the stack running, `AccountClosureIT` and `SessionsIT` were run while the stack's broker was watched. The user
service's suites set nothing about Kafka beyond that ignored switch, and the run showed:

- **The test JVM joined all seven of the running user service's consumer groups,** as a second member beside the
  stack's own, and was assigned partitions of `flashcart.order.events` and the payment topic. While it held them, an
  order confirmed on the stack would have been handled by the test's throwaway database and committed there. The
  real service would never send that email, and nothing would say so.
- **Four `AccountClosed` events went into the stack's `flashcart.user.events`,** published by the test's outbox relay.
  The stack's order and inventory services consume that topic and acted on closures of accounts that never existed
  there.

Neither failed a test. The tests were green and the stack was quietly wrong, which is the worst way for this to go.
It only happens on a machine with the stack up, which is the normal way to work on this project. CI's integration
jobs have no stack, so they never saw it.

The first probe of this reported nothing at all. Git Bash had rewritten the Kafka tool's path inside
`docker compose exec`, every call failed silently, and an empty answer looked like a clean one. Running it against
the stack's own known members first is what showed that.

## Decision

### The switch does what it says

`ConsumerFactories` builds each listener factory by hand, so Boot's `spring.kafka.listener.auto-startup` never
reached it. The factory now takes it from `KafkaProperties`. Production is unchanged, because the default is still
to start. `ConsumerFactoriesTest` covers both values, so the comments in `OrderIT`, `OutboxIT`, `AbstractPaymentIT`
and `AbstractShippingIT` are now true.

### Tests point at nothing unless they bring something

Each service with a stack default now has `src/test/resources/config/application.yml`. Spring reads
`classpath:/config/` as well as the root, and it takes precedence over it. The file sets:

- `spring.kafka.bootstrap-servers: localhost:1`, `listener.auto-startup: false`, and `flashcart.kafka.declare-topics:
  false`. That last one stops every context standing up an admin client to declare topics on whatever broker it
  finds, as inventory's base class had already worked out.
- `flashcart.outbox.relay.initial-delay: PT1H`, so the relay does not spend the run failing to send.
- `spring.datasource.url: jdbc:postgresql://localhost:1/...`. Every suite brings its own PostgreSQL today, but one
  that forgot would have run Flyway against the stack's database. Now it fails to start.

A suite's own service connection or `@TestPropertySource` still wins over the file, so the Testcontainers suites are
unaffected. `InventoryKafkaIT`, the one real broker round trip, switches the listeners, topics and relay back on for
itself.

Port 1, rather than leaving the address empty or disabling Kafka auto-configuration, because the services build
their own factories and an empty address fails differently from a dead one. A dead address is what
`AbstractInventoryIT` and `AuditRetentionIT` already used, one class at a time. The difference now is that it's the
module's default, so a new suite starts safe instead of having to remember.

### A missing file fails the build

`scripts/test-isolation-check.py` runs in CI beside the ADR index check. For every service whose `application.yml`
names a stack port, it requires the test config to name port 1 instead, with listeners and topic declaration off.
A new module, or one that gains Kafka later, fails there until it has the file. The script checks itself against
known answers first. Removing payment's file and switching user's listeners on were both seen to fail it.

## Consequences

- Running the tests with the stack up no longer changes the stack. The full integration run of all six modules was
  repeated against a running stack under the same probe: no member from the host joined any group, and no stack
  topic grew.
- A suite that needs Kafka or a database has to bring its own. It always did in practice, and now it's enforced.
- Logs from suites that start a listener against port 1 still show connection retries. The listeners are now off by
  default, so that is limited to suites that turn them back on without a broker, which is a mistake worth seeing.

## What this leaves open

- **Redis, Zipkin and mail were checked and need nothing.** The stack publishes Redis on 16379 and Zipkin on 19411,
  while the defaults are 6379 and 9411. Mailpit's SMTP port isn't published at all. If any of those mappings change
  to match the defaults, this file needs a line too. The check doesn't know about them.
- **The order service's suites still set `localhost:1` themselves.** That is harmless and left as written, and the
  module's file now says it for them.
