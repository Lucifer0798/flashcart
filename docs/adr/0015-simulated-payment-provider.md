# 0015 — The payment provider is simulated, and picks its outcome from the amount

**Status:** Accepted · **Date:** 2026-09-02 · **Phase:** 6

## Context

The interesting behaviour in this platform is almost entirely on the failure paths: the compensation
that releases stock when a card is declined, and the reconciliation that must *not* release it when
the provider goes quiet.

Neither is reachable against a real gateway on demand. A sandbox approves everything you ask it to,
and cannot be made to time out at the moment a test needs it to.

## Decision

`PaymentProvider` is an interface; `SimulatedPaymentProvider` chooses its outcome from the
**amount's cents**:

| Amount ends in | Outcome |
|---|---|
| `.13` | declined |
| `.99` | provider timeout |
| anything else | approved |

The thresholds are configuration, and the service publishes them on its `_info` endpoint.

## Alternatives considered

**A magic customer id, or a test header.** Both work in a test and neither survives the journey. The
order service does not forward arbitrary headers, and a magic customer would have to be plumbed
through the whole checkout. The amount already travels end to end untouched, because it is the one
value the flow genuinely needs.

**A test-only endpoint that forces the next outcome.** Convenient and dangerous: an endpoint that
makes payments fail is an endpoint that exists in production unless somebody remembers to remove it.

**Approve-only.** Smaller, and it would make `PAYMENT_FAILED` and `PAYMENT_TIMEOUT` unreachable
outside unit tests — leaving the compensation paths, the most valuable thing in Phase 6, unexercised
end to end.

**A real sandbox gateway.** Real semantics, needs credentials, and makes CI depend on a third party
being up. Worth doing behind the same interface when there is an account to use.

## Consequences

**Good.** Every outcome is reachable end to end with nothing but a product price — the compose stack
demonstrates a declined checkout compensating, and CI asserts it. The trigger survives every hop
because it rides on a field the system already carries.

**Bad.** A price ending in `.13` behaves differently from one ending in `.14`, which is surprising
until you know, and `9.99` is an extremely ordinary retail price to have chosen as the timeout
trigger. Both are configurable, both are published on `_info`, and both would need changing before
this stack met real money.

The simulation also cannot reproduce the case that matters most operationally: a provider that timed
out and then *did* charge. Resolving that needs the provider's own record, queried by idempotency
key, which is exactly what `PaymentReconciliationService` is left unable to do — deliberately, rather
than faking an answer and making the problem look solved.
