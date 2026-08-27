# 0002 — Spring Boot 4.0.8, not the newer 4.1.x

**Status:** Accepted · **Date:** 2026-08-27 · **Phase:** 1

## Context

At the time of writing, Spring Boot's latest GA is **4.1.1**. The latest Spring Cloud release train
is **2025.1.3**, which brings Spring Cloud Gateway **5.0.3** — and its BOM pins:

```xml
<spring-boot.version>4.0.8</spring-boot.version>
```

There is no release train targeting Boot 4.1 yet.

## Decision

Build against **Boot 4.0.8 + Spring Cloud 2025.1.3**, the pairing Spring Cloud actually tests.

Also pinned by that choice: Spring Framework 7.0.9, Spring Data JPA 4.0.7, Hibernate 7,
Flyway 11.14, Jackson 3.1.5, Testcontainers 2.0.5.

## Alternatives considered

**Boot 4.1.1 with Spring Cloud 2025.1.3.** A single minor bump, and it would very probably work.
"Probably" is the problem: the failure mode of an untested pairing is not a compile error, it is a
subtle runtime incompatibility inside the one component every request passes through. Buying a minor
version at that price is a bad trade.

**Drop Spring Cloud Gateway and hand-roll routing.** Frees the version constraint entirely, at the
cost of reimplementing predicates, filters, retries and rate limiting — all of which later phases
need.

## Consequences

**Good.** Every component is on a combination its maintainers test together. Gateway 5.x is fully
available, including the `DedupeResponseHeader` filter and the read-only routes actuator endpoint.

**Bad.** One minor version behind the newest Boot. Upgrading is gated on Spring Cloud, not on us.

**Notes for whoever does the upgrade.** Boot 4 moved a lot; these bit during Phase 1 and will bite
again:

- Auto-configurations were split out of `spring-boot-autoconfigure` into per-technology modules.
  `flyway-core` alone gives the library with nothing wiring it to the datasource — migrations
  silently never run. Use `spring-boot-starter-flyway`.
- `TestRestTemplate` moved to the separate `spring-boot-resttestclient` artifact and the
  `org.springframework.boot.resttestclient` package, and needs `spring-boot-restclient` alongside it
  or its auto-configuration fails to resolve.
- Jackson 3 split its packages: annotations stay at `com.fasterxml.jackson.annotation`, databind
  moved to `tools.jackson.databind`.
- Spring Framework 7 renamed `HandlerMethodValidationException.getAllValidationResults()` to
  `getParameterValidationResults()`.
- Spring Data JPA 4's `Specification.and`/`allOf` reject `null` elements, so the old "return null for
  an absent filter" convention now throws. Use `Specification.unrestricted()`.
- Spring Cloud Gateway 5 replaced `spring-cloud-starter-gateway` with explicit
  `spring-cloud-starter-gateway-server-webflux` / `-webmvc` variants.
