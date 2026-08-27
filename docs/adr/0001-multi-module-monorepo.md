# 0001 — One multi-module Maven repo, not seven repos

**Status:** Accepted · **Date:** 2026-08-27 · **Phase:** 1

## Context

FlashCart is seven deployable services plus a shared contract library. The conventional
microservices answer is one repository per service, each with its own build and release cadence.

That answer is motivated by team autonomy: independent repos stop one team from blocking another.
FlashCart has no independent teams. What it does have is a set of contracts — the order state
machine, the event envelope, the error shape — that every service must agree on, and that will
change repeatedly over the next ten phases.

## Decision

One repository, one reactor build, modules under `services/`:

```
flashcart-parent
├── services/common      library jar, not an executable
├── services/gateway
├── services/catalog
└── services/{order,user,payment,inventory,shipping}
```

`flashcart-common` sets `spring-boot-maven-plugin` to `skip`, since repackaging it as a fat
executable jar would make it unusable as a dependency.

## Alternatives considered

**Seven repositories with `flashcart-common` published to a registry.** Correct at organisational
scale, and wrong here. A change to the order state machine would become: change common, release
common, bump the version in six repos, merge six PRs — for a change that in a monorepo is one commit
the compiler verifies across every consumer at once.

**Seven repositories with the contracts duplicated.** Removes the release dance by removing the
guarantee. The contracts are precisely the thing that must not fork.

## Consequences

**Good.** A contract change is one atomic, compiler-checked commit. CI builds every service against
every other service's version of the shared code, so a breaking change cannot land unnoticed. One
`docker compose up` runs the platform.

**Bad.** The whole reactor builds even for a one-service change — tolerable at this size, and the
Docker layer cache already keeps image builds incremental. Deployment granularity has to be
maintained deliberately: the shared repo makes it easy to *accidentally* couple services at runtime,
which the database-per-service boundary ([ADR 0003](0003-database-per-service.md)) exists to prevent.

**Revisit when** more than one team owns services here, or when services genuinely need independent
release cadences.
