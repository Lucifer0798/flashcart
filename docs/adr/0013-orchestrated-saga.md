# 0013 — The saga is orchestrated by the order service, not choreographed

**Status:** Accepted · **Date:** 2026-09-02 · **Phase:** 6

## Context

A checkout spans four services: order, inventory, payment, shipping. Each step can fail, and most
failures need a compensating action somewhere else. Something has to know the sequence.

Two shapes are available. In **choreography**, each service listens for the previous one's event and
decides for itself what to do next. In **orchestration**, one service owns the sequence and sends
commands.

## Decision

The order service orchestrates. It owns `OrderSaga`, sends commands (`ReserveInventory`,
`RequestPayment`, `CommitInventory`, `CreateShipment`, `ReleaseInventory`) and reacts to the events
those produce. Inventory, payment and shipping do their job and report; none of them knows what
happens next.

Topics are split accordingly: `*.commands` carries instructions with exactly one consumer, `*.events`
carries facts anyone may subscribe to.

## Alternatives considered

**Choreography.** More decoupled, and closest to the flow diagram in the original brief — payment
listens for `InventoryReserved`, shipping listens for `PaymentCompleted`, and so on. Rejected for one
reason that outweighs the coupling argument: **the sequence would exist nowhere in the codebase.**
Answering "why is this order stuck" would mean reading four services' listeners side by side and
reconstructing the intended order from what each happens to subscribe to. With orchestration the
whole flow, including every compensation, is one readable class next to the state machine that
constrains it.

Choreography also scatters compensation. When payment declines, something must release the
inventory; in a choreographed system that is inventory listening for `PaymentFailed`, which means
inventory knows about payments.

**A dedicated saga/workflow service.** Correct at larger scale and more machinery than this earns.
The order aggregate already owns the state machine, so the sequence and its constraints live
together rather than in two services that must agree.

## Consequences

**Good.** The flow is readable in one place. Compensation is explicit rather than emergent. Adding a
step means changing one class, not teaching two services about each other. The order service already
owned the state machine, so the saga's legal moves are enforced by the same table.

**Bad.** The order service is now a coordinator as well as an aggregate, and it is the one service
that knows about all the others — the coupling did not vanish, it concentrated. If it is down, no
order progresses, whereas choreography would keep the individual steps running.

Every step costs two messages (a command out, an event back) rather than one, which is more topics
and more latency per hop than choreography's single fan-out.
