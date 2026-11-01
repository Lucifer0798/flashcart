# 0061 — The first theft is the one that counts

**Status:** Accepted · **Date:** 2026-11-01 · **Phase:** post-roadmap · **Builds on [ADR 0042](0042-every-alert-went-quiet-together.md), [ADR 0058](0058-a-guess-costs-more-than-a-hash.md), [ADR 0059](0059-a-session-ends-within-fifteen-minutes.md) and [ADR 0060](0060-the-reset-form-is-not-a-mailing-list.md)**

## Context

The last three security records each counted the thing they defend against:

- a refresh token presented again after use, which means a stolen session
  ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md));
- sign-ins refused for too many failures ([ADR 0058](0058-a-guess-costs-more-than-a-hash.md));
- reset requests refused for one client asking too often ([ADR 0060](0060-the-reset-form-is-not-a-mailing-list.md)).

Each defence acts on its own: it revokes the session or refuses the request. None of them told a person.
The counters reached the metrics endpoint and stopped there, so an attack in progress, or a session
already stolen, was visible only to someone who happened to be looking at the right graph.

Writing the alerts turned up a second problem, one that would have made the first alert useless.

## Decision

### Three warnings, because the defence has already acted

| Alert | Fires when | Why that line |
|---|---|---|
| `RefreshTokenReused` | any reuse in 15 min | one is a stolen session, and there is no benign cause |
| `SignInsThrottled` | more than 10 throttled sign-ins in 10 min, all limits together | one user's own typos account for a handful; ten is somebody trying |
| `ResetRequestsThrottled` | any refusal in 10 min | nobody who forgot a password asks eleven times an hour |

All three are `warning`, routed to the ticket receiver. Each defence has already done its job, so nobody
needs waking. What is needed is a person to see who and how while the logs still say: which session was
stolen, which account is targeted, which client is mailing a list. Each description says where to look.

`SignInsThrottled` sums across its three limits. Credential stuffing and guessing one account show up under
different limits, and an attack that spreads across them is still one attack.

### The counters exist at zero from startup

The three counters were created on first use, with `meters.counter(...).increment()`. A counter created
that way enters Prometheus already at 1, with no earlier sample. `increase()` reads a series' first sample
as its starting point, so a series born at 1 shows an increase of zero. **The first refresh-token reuse
after every restart, quite possibly the only one, would never have fired `RefreshTokenReused`.** On the
running stack, the user service's metrics showed none of the three series at all until each had happened
once.

They are now registered at zero when the service starts: the reuse counter, the sign-in counter under each
of its three limits, and the reset counter. A rule test keeps the reason in view. A series that first
appears at 1 fires nothing, and the test expects exactly that, so whoever is tempted to drop the
registration as dead code meets the case it exists for.

## Verified

- `rules.test.yml`, 7 cases, which check that:
  - one reuse fires `RefreshTokenReused`, a series born at 1 does not, and no reuse does not;
  - throttling spread across two limits adds up and fires `SignInsThrottled`, while a few refusals for
    one user do not;
  - one reset refusal fires `ResetRequestsThrottled`, and requests inside the limit do not.

  `promtool check` finds 14 rules, the unit tests pass, and the new alerts route to `ticket`.
- Rule mutations, all 4 caught: reuse needing two; throttling judged per limit and not summed; the
  threshold low enough for a forgetful user to trip it; reset refusals needing two.
- `UserIT`: in a context where nothing reuses a token or is throttled, all five series exist, which only
  registration at startup can explain. Removing either registration fails it.
- Live, on the compose stack:
  - after the rebuild, the user service's metrics showed all five series at 0 from startup; before, there
    were none;
  - Prometheus loaded the three rules;
  - a refresh token used twice got 401, and the counter went from 0 to 1;
  - `RefreshTokenReused` was firing in Prometheus about 30 seconds later, and Alertmanager routed it as a
    `warning` to the `ticket` receiver.

## Consequences

**Good.** A stolen session, password guessing and a mailing-list attempt each reach a person, starting with
the first occurrence after a restart.

**Accepted.**

- The thresholds for `SignInsThrottled` (ten in ten minutes) and `ResetRequestsThrottled` (any) are
  judgements, not measurements. They are set to say "somebody is trying", and are the first thing to tune
  if they prove noisy.
- These alerts resolve once their window passes, unlike `RefundAbandoned`
  ([ADR 0041](0041-the-alert-that-forgot.md)), which watches a state. Here each event is already dealt
  with by the time it fires. The ticket is the record, and nothing stays wrong for an alert to keep
  showing.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
