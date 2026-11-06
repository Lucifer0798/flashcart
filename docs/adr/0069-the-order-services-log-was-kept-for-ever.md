# 0069 — The order service's log was kept for ever

**Status:** Accepted · **Date:** 2026-11-06 · **Phase:** post-roadmap · **Builds on [ADR 0026](0026-deleting-an-audit-record-is-a-decision.md) and [ADR 0028](0028-looking-is-not-acting.md)**

## Context

[ADR 0028](0028-looking-is-not-acting.md) gave the order service an `operator_access_log` and no retention sweep.
Nothing said so. Payment, shipping, user and inventory each have the sweep and compose's `P365D`, so
`AUDIT_RETENTION` looked like a platform setting while the order service ignored it.

## Decision

The order service gets the same `OperatorAccessRetention` bean as the others, and compose sets
`FLASHCART_AUDIT_RETENTION: ${AUDIT_RETENTION:-P365D}` for it.

### This changes what the compose stack does

**A compose stack will now delete order access records older than a year, and they cannot be recovered.** Until now
it kept them for ever. Once a row is gone, it is also gone from support's access-log reader, from a customer's view of
who looked, and from their data export.

The first sweep runs five minutes after start and removes up to 500 rows, then 500 more every six hours. A long-lived
stack with a large backlog therefore drains over time rather than at once. To keep everything on compose, set
`AUDIT_RETENTION=PT0S`, because a zero window means keep.

Any other deployment is unchanged. The application default is still to delete nothing unless
`flashcart.audit.retention` is set, as ADR 0026 decided. It gains the startup line saying so.

## Verified

- `AuditRetentionIT`, on real PostgreSQL, uses the bean the service builds, not one the test constructs. The sweep is
  on the schedule. With nothing configured, a row 4000 days old survives. With `P30D` and a batch size of 2, rows 31,
  100 and 4000 days old go in batches of 2, 1 and 0, and rows 29 and 0 days old stay.
- Mutations, each predicted before it ran, and each red in only the predicted tests: bean removed (all three); sweep
  not scheduled; a default window on the property; window ignored; the setting not read; the batch size not read.
- Removing `@EnableScheduling` from `OrderConfig` stayed green, as predicted, because the outbox's auto-configuration
  also enables scheduling. Removing `@Scheduled` from the sweep is what the test catches.
- The platform job checks that the order service logs that it keeps records for 365 days. That grep was run against a
  running stack: it matches payment's log, and it finds nothing in the log of an order service without this change.
- **Not done:** the grep has not been seen to fail with only the compose line removed from a stack that has the bean.
  No test here has watched a scheduled tick delete a row on a running stack.

## Consequences

**Good.** `AUDIT_RETENTION` now means the same thing for all five services that keep this log.

**Accepted.** Audit evidence on a compose stack now expires where it did not before, for the reason ADR 0026 calls a
worked example rather than a default.

## What this leaves open

- **`spring.kafka.listener.auto-startup=false` does nothing in this module's tests.** `ConsumerFactories` builds its
  own listener factories and does not read it, and the default broker is `localhost:19092`. A first run of
  `AuditRetentionIT` joined a running compose stack's consumer groups and caused a rebalance, so this test now points
  at a dead address. `OrderIT` and `OutboxIT` still set the property, and their comments still say it turns listeners off.
