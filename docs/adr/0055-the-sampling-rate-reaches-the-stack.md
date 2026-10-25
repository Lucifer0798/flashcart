# 0055 — The sampling rate reaches the stack

**Status:** Accepted · **Date:** 2026-10-25 · **Phase:** post-roadmap · **Closes an item left open by [ADR 0049](0049-loose-ends.md)**

## Context

Every service reads its trace sampling rate from `TRACE_SAMPLING`, defaulting to every trace.
`compose.yaml` passed it to none of them. [ADR 0049](0049-loose-ends.md) found this the hard way: its
first check of the outbox's stored sampled flag set the variable in the shell, changed nothing inside the
containers, and the "unsampled" orders stored `01`. The check had to be rerun with an override file, and
the gap was carried as open through five records since.

A setting that is silently ignored is worse than a missing one, because it looks like it worked.

## Decision

`TRACE_SAMPLING: ${TRACE_SAMPLING:-1.0}` goes in the shared `service-env` anchor, which all seven
services merge.

- **All seven at once, not per service.** A trace kept at the gateway and dropped by the order service
  is the broken trace [ADR 0020](0020-a-trace-must-survive-the-outbox.md) exists to prevent. One
  setting for the stack keeps the decision in one place.
- **1.0 by default**, the same as the services' own default. A local stack wants every trace, and
  nothing about running the stack changes unless someone asks.

While there, the compose file's header claimed every host port sat in 15000–19000. ADR 0049 corrected
that claim in the README, and it now states the same rule here: the conventional port with a 1 in front.

## Verified

- `docker compose config` resolves `TRACE_SAMPLING` to `1.0` for all seven services, and to `0` for all
  seven with `TRACE_SAMPLING=0` set in the shell.
- Live, with `TRACE_SAMPLING=0 docker compose up -d` and no override file, the order container's
  environment has `0`. All five outbox messages for an order (`ReserveInventory`, `RequestPayment`,
  `OrderConfirmed`, `CommitInventory`, `CreateShipment`) stored the sampled flag `00`. Recreated at the
  default, the container has `1.0` and the same five stored `01`. This is the check ADR 0049 could only
  run with an override.

## Consequences

**Good.** The variable every service documents now does what it says when the stack is started with it.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
