# 0003 — A database per service, sharing one instance locally

**Status:** Accepted · **Date:** 2026-08-27 · **Phase:** 1

## Context

Six services need persistence. Under load, the interesting write contention is concentrated almost
entirely in one of them (inventory), while the others are comparatively quiet.

## Decision

One PostgreSQL **database** per service — `flashcart_catalog`, `flashcart_order`,
`flashcart_inventory`, and so on — created at first container start by
`infra/postgres/init-databases.sql`. Locally they share a single PostgreSQL instance; in production
they are separate instances.

All six databases are created in Phase 1, including the five whose schemas arrive in later phases.

## Alternatives considered

**One shared database with a schema per service.** Cheaper to operate and allows cross-service joins
and transactions — which is exactly the problem. The moment a join is possible, someone writes one,
and the service boundary becomes a naming convention rather than a boundary. It also means one
service's runaway query can starve every other service's connection pool.

**One database, one schema, shared tables.** A distributed monolith with extra network hops.

## Consequences

**Good.** No service *can* read another's tables, even by accident: it is enforced by permissions,
not discipline. Each service's schema evolves on its own Flyway timeline. Inventory can be scaled or
tuned — connection pool, `work_mem`, eventually its own hardware — without touching anyone else.
Creating all six up front means bringing a service to life is a code change, never an infrastructure
change.

**Bad.** No cross-service transactions, and no cross-service joins. Consistency across services must
be achieved with events and sagas, which is more machinery than a join — Phases 5, 6 and 8 exist to
build it. Any query spanning services becomes an API call or a read model.

The local single-instance compromise means a laptop does not see production's isolation
characteristics. Acceptable: the *logical* boundary, which is the one that shapes the code, is
identical.
