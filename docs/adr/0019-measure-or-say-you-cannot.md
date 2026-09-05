# 0019 — Report what the measurement can support, and no more

**Status:** Accepted · **Date:** 2026-09-05 · **Phase:** 10

## Context

Four earlier ADRs deferred a decision here rather than argue it. ADR 0006 kept `PESSIMISTIC_LOCK`
behind a switch "so Phase 10 can measure the difference". ADR 0008 named the atomic counters as "the
first thing to look at if Phase 10 finds the path too slow". `DisabledAvailabilityGate` exists so the
gate can be measured against its own absence. ADR 0017 wondered whether relay lag would justify change
data capture.

All four were deliberately left as questions for a phase that would answer them with numbers. The
problem is that a phase named "load testing" has a strong pull toward producing a ranking whether or
not the data supports one, because a table with a winner in it looks like a finished job.

## Decision

**Measure on the machine available, and state plainly what that machine can and cannot distinguish.**

The correctness result is reported as fact: every configuration sold exactly 100 units out of 2000
concurrent attempts, with no errors. That claim is supported — it is a count, checked against
PostgreSQL, and it would have failed loudly if it were wrong.

The throughput comparison is reported as **unavailable**. Run-to-run variance within one
configuration spans 271–460 req/s, which is wider than any gap between configurations. No ranking of
`ATOMIC_UPDATE` against `PESSIMISTIC_LOCK`, and no throughput claim for the availability gate, is
recorded anywhere in this repository as a result of Phase 10.

This also retires a number that was quoted too confidently: Phase 7's note that
`InventoryConcurrencyIT` fell from ~58s to ~8.8s with the gate enabled. That was a test-suite runtime
including container lifecycle, not a benchmark, and it does not reproduce under controlled load. It
is not a performance figure and should not be repeated as one.

**The load harness must prove itself before its numbers count.** `run.sh` fails a run when k6's grant
count disagrees with the `reserved` column, and refuses to record a run whose configuration it cannot
read back from `/_info`.

## Alternatives considered

**Publish the numbers with a caveat.** The obvious move, and the wrong one. A table with four numbers
and a footnote saying they are noisy gets quoted as a table with four numbers. If the data cannot
support a ranking, the honest artefact is the absence of a ranking.

**Run on dedicated hardware to get a real answer.** The correct way to answer the throughput question,
and out of scope for a laptop-hosted project. The switches remain in place and the harness is
committed, so the measurement can be made the day a suitable machine exists.

**Tune until a difference appears.** Rejected on sight. Searching configurations until one ordering
emerges is how a benchmark becomes an argument with extra steps.

**Drop `PESSIMISTIC_LOCK` since it cannot be shown to be slower.** Tempting, and premature: the
comparison has not been made, so removing the alternative would be acting on the result that was
explicitly not obtained. It stays, still switchable, still unproven either way.

## Consequences

**Good.** Nothing in this repository now claims a performance result it cannot defend. The
anti-oversell guarantee, which is the platform's actual thesis, is demonstrated under real concurrency
rather than asserted from unit tests. The harness caught two genuine defects on the way — load
shedding reporting itself as a 500, and a measurement that counted idempotent replays as sales — which
is a better return than a throughput table would have been.

**Bad.** The question ADR 0006 asked is still open, and anyone reading it will find it still open.
That is the accurate state of knowledge, but it does mean the `strategy` switch continues to carry a
configuration path that nobody has a measured reason to choose between.

The load runs also cannot go in CI in any meaningful form. A shared runner is even noisier than this
laptop, so CI asserts only that the harness executes and the invariant holds at small scale — that the
tool has not rotted, not that the platform is fast.
