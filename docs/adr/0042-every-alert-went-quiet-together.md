# 0042 — Every alert went quiet together

**Status:** Accepted · **Date:** 2026-10-04 · **Phase:** post-roadmap · **Follows from [ADR 0041](0041-the-alert-that-forgot.md)**

## Context

[ADR 0041](0041-the-alert-that-forgot.md) found that the critical refund alert had resolved itself for
as long as it existed, and fixed it by alerting on a gauge of what is owed now. It closed by noting
that no alert rule had a test: the one it fixed had been wrong since it was written, and nothing could
have said so.

Reading all eight rules for this record found a wider version of the same fault. **Every one of them
reads a metric the service itself exposes, and none of them watches whether the service is being read
at all.** When a service stops being scraped its series go stale, and every alert built on them
resolves — not because the fault was fixed, but because the evidence disappeared.

Demonstrated on the running stack, with a refund still owed (`REFUND_FAILED`, at the attempt cap):

| | `RefundAbandoned` | anything else |
|---|---|---|
| payment running | firing | — |
| `docker compose stop payment` | **resolved after 30s** | **nothing fired** |

The row still said the money was owed. The one critical alert about it had gone quiet, and nothing had
taken its place. ADR 0041's fix made the alert resolve only when the refund was settled — or, it turned
out, when the service that would report it was not there to ask.

The same holds for every rule: a stalled outbox reads as healthy while its service is down, as does an
availability gate that has gone blind.

## Decision

**A rule for the thing every other rule depends on, and a test for every rule.**

### `ServiceDown`

```yaml
expr: up{job="flashcart"} == 0
for: 1m
labels: { severity: critical }
```

Critical, because it is not one fault but the loss of every alert for that service. One minute, because
a container restart takes about that long and is not an outage; any longer would leave the service's
other alerts blind for longer still. Its description says what it is hiding, and that those alerts will
fire again on recovery if their causes remain.

Verified live: with payment stopped, `ServiceDown` was the only alert firing within 90 seconds; started
again, it resolved and `RefundAbandoned` returned within 45, because the money was still owed. That
hand-off is the property, and it is now also a test.

### Not making each alert robust to its own absence

The alternative was to give every rule an `or absent(...)` arm. Rejected: an alert that fires when its
metric is missing claims something specific — "a refund is owed" — that nobody knows while the service
is down. One rule that says "this service cannot be read, so its alerts cannot be trusted" is both true
and actionable, and it covers rules not yet written.

### Rule tests, in CI

`infra/prometheus/rules.test.yml` holds 17 `promtool` test cases across all nine rules. Each rule has
a case that must fire and a **healthy case of the same shape that must not**: a rule tested only for
firing is satisfied by `vector(1)`. `infra/prometheus/test-rules.sh` runs `promtool check rules` on the
real file and `promtool test rules` on a copy with annotations removed — promtool compares annotations
exactly, which would make every reworded description a failing test. The image is read from
`compose.yaml`, so the rules are tested by the engine that evaluates them. A new CI job, **Alert
rules**, runs it.

## Verified

Twelve mutations, one per way a rule could be loosened, each caught by the case meant to catch it:

| mutation | caught by |
|---|---|
| `RefundAbandoned` back on the abandon counter | still firing at 115 min |
| `ServiceDown` matching no job | not firing at 5 min |
| `ServiceDown` without its minute of grace | firing at 3 min, during a restart |
| `OutboxStalled` at 30s instead of 120s | a busy relay at 90s fires |
| `CancellationsUnanswered` without its `for` | a single re-ask fires |
| `OutboxSendFailing`, `SagaTransitionsDeclined`, `OperatorReadsUnrecordable` at `>= 0` | their healthy cases fire |
| `AvailabilityGateBlind` at `> 0` | half a blind answer a second fires |
| `AvailabilityGateBlind` without its `decision` filter | ordinary refusals fire |
| `RefundRefused` without its `outcome` filter | completed refunds fire |
| `RefundAbandoned` at `>= 0` | still firing after settlement |

**Two of them survived the first round**, which is the reason the round exists:

- The "busy relay" case for `OutboxStalled` happened to dip below the mutated threshold at the one
  moment it was evaluated. It now holds at 90 seconds for longer than the rule's `for`.
- The "single re-ask" case for `CancellationsUnanswered` was evaluated after the re-ask had left the
  rate window — Prometheus 3 ranges exclude their left edge — so it could not see a missing `for`. It
  is now evaluated at six minutes, while the re-ask is still in the window.

Both tests passed against the correct rules and proved nothing about them.

## Alternatives considered

**Compare annotations in the tests.** Exact matching would fail every test on a reworded sentence.
Templates are still parsed by `promtool check rules` against the unmodified file.

**Test only that each rule fires.** Catches a rule that never fires; misses one that always does,
which is the more common way a rule rots — and the way a page gets silenced.

**An Alertmanager in the stack.** These alerts currently fire into Prometheus's own UI and nowhere
else. That is a real gap, and it is a different one: routing an alert does not help if the alert
cannot fire.

## Consequences

**Good.** A service going down is now itself an alert, so silence means healthy rather than possibly
unreadable. Every rule is tested on what it is for and on what it must leave alone, and a rule change
that breaks either fails CI.

**One more job in CI**, which takes seconds.

**Still open.**

- No Alertmanager: firing alerts are visible only in Prometheus.
- If Prometheus itself is down, nothing fires; nothing watches the watcher.
- Production accounts still get `OPERATOR`; the load harness still signs in as the operator.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
