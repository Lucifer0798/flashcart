# Architecture decision records

One file per decision that was not obvious, written when it was made. Each records what was decided,
what else was on the table, and what the decision costs — the last part being the one that matters
when someone revisits it in six months.

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-multi-module-monorepo.md) | One multi-module Maven repo, not seven repos | Accepted |
| [0002](0002-spring-boot-and-cloud-versions.md) | Spring Boot 4.0.8, not 4.1.x | Accepted |
| [0003](0003-database-per-service.md) | A database per service, sharing one instance locally | Accepted |
| [0004](0004-derived-flash-sale-phase.md) | Flash-sale liveness is derived, never stored | Accepted |
| [0005](0005-catalog-owns-no-stock.md) | Catalog holds no stock count | Accepted |
