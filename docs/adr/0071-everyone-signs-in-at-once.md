# 0071 — Everyone signs in at once

**Status:** Accepted · **Date:** 2026-11-11 · **Phase:** post-roadmap · **Measures [ADR 0058](0058-a-guess-costs-more-than-a-hash.md) and [ADR 0059](0059-a-session-ends-within-fifteen-minutes.md) under load, the way [ADR 0019](0019-measure-or-say-you-cannot.md) asks**

## Context

The load harness had only ever tested reservations. Everything built for sign-in since ADR 0057 had never been under
load: BCrypt, the throttle table, rotating refresh tokens and the grace window. That is the part every shopper
touches first when a sale opens, all at the same moment.

`load/sign-in.sh` now seeds N accounts sharing one real BCrypt hash (cost 10), and k6 has every one of them sign in
at once and then refresh once. A set share of them mistype their password first. Then it checks PostgreSQL:

- sessions started equal the sign-ins k6 counted;
- tokens issued equal sign-ins plus refreshes;
- failure rows equal three per refused typo, less one for each typist who then got in, because a success clears
  that pair's row.

The run fails if any of these disagree. It deletes everything it created, including the failure rows, which would
otherwise hold the per-client limit against the next run.

Every request comes from the k6 container, so the platform sees one client address. That is not a flaw in the
harness. It is the case it exists to measure: an office, or a mobile carrier's NAT, where many shoppers share one
address.

**One machine, so no throughput figures.** Docker's 16 cores run k6, the gateway, PostgreSQL and the user service
together. One sign-in on an idle service takes 77–200 ms. Under load each appears to cost about 180 ms of
user-service CPU, but that can't be separated from what everything else on the machine is doing. Per ADR 0019,
these are what held from run to run: who got in, how long the last one waited, and what failed.

## What it found

### 1. A shared address locks out its honest shoppers

Burst size 1,000, with 5% mistyping once before typing it right:

| Run | Signed in | Right password refused (429) |
|---|---|---|
| 1 | 605 | 395 |
| 2 | 686 | 314 |

The per-client limit is 30 failures per 15 minutes (ADR 0058). Concurrent typos overshoot it, as that ADR accepts:
36 and 40 were counted before it closed. From then on, every sign-in from that address was refused, right
passwords included, for up to fifteen minutes. A third to two-fifths of a sale's shoppers behind one carrier address
would be locked out by other people's typos.

That limit exists to stop credential stuffing, which is many accounts with one failure each from one address. By
count alone, that looks exactly like this. **Not changed here.** The chosen fix is to credit an address for the
distinct accounts that signed in successfully from it. It changes ADR 0058's defence, so it gets its own record,
and it will be measured with this harness.

### 2. BCrypt starves the connection pool

With no typos, everyone eventually got in up to 1,000 shoppers, but slowly: at 1,000 the last waited 14 s. Above that,
some did not get in at all.

| Shoppers | Right password failed | Slowest sign-in |
|---|---|---|
| 2,000 (three runs) | 81, 23, 0 | 32 s, 26 s, 21 s |
| 3,000 (two runs) | 90, 40 | 42 s, 47 s |

The failures were the user service shedding, with "no database connection available in time" logged 184 times in one
run, and refreshes failing alongside sign-ins. Sign-in runs without a transaction, so no connection is held during
the hash. But with every request thread hashing at once and all cores busy, a thread holding one of the pool's ten
connections for a few milliseconds' query was descheduled for far longer. The pool ran dry with the database idle.
Refreshes, which do no hashing, waited a median 9–21 s for the same reason.

### 3. The gateway called an overloaded service broken

The rest of the failures were the user service resetting connections it had queued too long. The gateway had no
error handler, so each reset reached the shopper as **500**. The platform has answered deliberate shedding with 503
and `Retry-After` since Phase 10, because a 500 says "broken" and invites nobody to try again. The gateway undid that
for anything that failed on the network.

## Decision

### No more hashes at once than there are cores

`BoundedPasswordEncoder` wraps BCrypt behind a fair semaphore sized to the CPUs the JVM sees
(`flashcart.passwords.concurrency`, 0 for that default). Hashing as many at once as there are cores is as fast as it
goes, so the cap costs no throughput. A request that would have fought for a core waits for one instead, holding
nothing while it waits, and the threads that do hold a connection get a core when they need one. It covers every
hash: registration, sign-in, changing and resetting a password, and closing an account. The wait has no timeout of
its own, because only request threads wait. At most Tomcat's 200 can, which is a second or two of hashing.

### An upstream that cannot be reached is a 503

`UpstreamFailureHandler`, ahead of Boot's default handler, turns a refused, timed-out, reset or prematurely closed
connection to a service into 503 with `Retry-After: 1` and the usual error body. Anything else, including a real 500
from a service, passes through unchanged. A reset can come after the request was sent, so the service may have done
the work. 503 is still the honest answer from where the gateway stands: it does not know, and retrying is how the
client finds out. Writes that a shopper would retry take idempotency keys, and sign-in is safe to repeat.

## Measured after

The same machine, a discarded warm-up run first, then the same loads:

| Shoppers | Right password failed (before → after) | Slowest sign-in (before → after) |
|---|---|---|
| 2,000 | 81, 23, 0 → 17, 0, 0 | 21–32 s → 24–39 s |
| 3,000 | 90, 40 → 0, 0 | 42–47 s → 31–35 s |

Over five runs at 2,000–3,000, failures went from 234 to 17. Everyone got in at 3,000 both times. The median refresh
there fell from 20–21 s to 10–14 s.

**Reduced, not removed.** The 17 were the pool again, all shed within about 150 ms of one moment. That looks like a
pause, such as a collection or a compilation, catching the pool short. It does not look like the steady starvation
from before. Ten connections and a two-second wait are the knobs if it matters. They are left alone because the
cap, not the pool, was what was wrong.

With the user service stopped, a sign-in through the gateway now answers 503 with `Retry-After` and a correlation id.
The after-runs produced no resets, so the reset case is covered by the handler's tests rather than seen live.

Mutations of both fixes, eight of them, were each caught by the test meant to catch them. They covered no cap, a cap
of one, `matches` skipping the cap, a cap of zero accepted, a reset not recognised, every error treated as an
upstream failure, a committed response rewritten, and `Retry-After` dropped.

## Consequences

- A sale's sign-ins now queue for the CPU in order. At 3,000 at once the last shopper still waits about half a
  minute, because that is how long 3,000 hashes take on this machine. More cores or more instances are the answer to
  that, and nothing in a single process can change it.
- A client that sees a 503 from the gateway knows to come back, whichever service was behind it.

## What this leaves open

- **The shared-address lockout above.** It is the most serious thing this found, and it is the next change.
  *Since fixed by [ADR 0072](0072-an-address-earns-its-typos.md), whose same-day comparison also found `main`
  shedding 8–801 of 3,000: the two clean runs above were a good day, not a guarantee.*
- **A wait for a core is invisible.** No metric counts requests waiting for a permit. If sign-in is slow in
  production, nothing would say it was waiting for the CPU and not the database.
- **One service, one machine.** Several user-service instances would share the throttle table and each have their own
  pool and cores. None of that was measured.
