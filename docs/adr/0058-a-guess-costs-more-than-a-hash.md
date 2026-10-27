# 0058 — A guess costs more than a hash

**Status:** Accepted · **Date:** 2026-10-27 · **Phase:** post-roadmap · **Builds on [ADR 0049](0049-loose-ends.md) and [ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md)**

## Context

Sign-in has always refused without saying why, and has run BCrypt even for an address with no account,
so its timing does not say either. Nothing limited how often it could be asked. BCrypt's cost slows each
guess by a fraction of a second, but a guesser can run guesses in parallel, and credential stuffing (one
leaked password tried against many accounts) needs only one guess per account. Every guess was free to
repeat at the platform's full speed.

## Decision

### Three limits, because each catches what the others miss

| Limit | Default | Stops |
|---|---|---|
| one account, one client | 5 failures / 15 min | one place guessing one account |
| one client, any account | 30 failures / 15 min | credential stuffing: many accounts, once each |
| one account, any client | 50 failures / hour | a guess spread across many machines |

The tight limit is per account **and** client. Someone guessing one account from one place is stopped
after five tries, and the account's real owner, signing in from somewhere else, never notices. The
per-account limit is the one an attacker could use to lock somebody out by failing on purpose from
many places, so it is set high. While it holds, a password reset ([ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md))
still works.

A refused attempt gets 429 `TOO_MANY_ATTEMPTS` with `Retry-After`, the seconds until the oldest counted
failure leaves its window. The message is the same whichever limit held, so a guesser cannot tell
whether the account is being guessed from elsewhere.

### Checked before the password, and refused even when it is right

The limits are checked before the password is compared. A throttled attempt is refused even with the
right password. Otherwise the throttle would slow only the wrong guesses and still confirm the right
one, which is the one that matters. BCrypt is not run for a throttled attempt either.

A success clears that client's count for that account, so a mistyped password or two does not linger.
It does not clear the other two counts: one success from one place says nothing about the rest.

### It says no more than sign-in does

Failures are counted against the address as typed, whether or not it has an account. An address nobody
registered is throttled exactly as a real one is, so the 429 cannot be used to ask whether somebody shops
here, which sign-in itself was built not to answer.

### Kept as hashes, briefly

`sign_in_failures` holds a SHA-256 of what is limited (the address, the client, or both) and when. It
never holds the addresses people typed, many of them mistakes or other people's, nor where they typed
them from. Rows older than the longest window can no longer count and are pruned every five minutes.

The service's own Postgres, not Redis. The volume is a row per failure, and adding a dependency to a
service that has none would cost more than the speed would buy here.

### The client is the gateway's address for it

Through the gateway, the client is the right-most `X-Forwarded-For` entry: the address the gateway saw,
appended after anything the client wrote. Entries to its left are the client's own to write, and trusting
them would let a guesser pick a fresh address for every attempt. Called directly on its published port,
the service sees no gateway, and that last entry is the caller's to write too. That is the same exposure
every service's published port carries in this stack ([ADR 0049](0049-loose-ends.md)), and the
per-account limit still holds whatever the caller claims.

### Sign-in runs without a transaction

A failure is recorded, then sign-in throws. Inside a transaction, the throw would roll the record back,
and no failure would ever count. Before this, sign-in was read-only, so each write now commits on its
own.

## Verified

- `SignInThrottleIT`, 8 tests at small limits (3, 6, 9), which check that:
  - one client guessing one account is stopped, even with the right password; `Retry-After` is within
    the window, and the owner elsewhere signs in;
  - a success clears the slate for that client;
  - stuffing six accounts once each from one client stops a seventh from it, and not from others;
  - nine places guessing one account stop everyone, while a password reset is still accepted;
  - an unknown address is throttled with the same status and message as a known one;
  - only hashes are stored;
  - failures age out of the window and are pruned;
  - forged left-hand `X-Forwarded-For` entries do not help.
- Mutations, all 9 caught: the limits checked only after a wrong password; failures not recorded; a
  success clearing nothing; the left-most forwarded address trusted; the tight limit keyed by client
  alone; the account limit not checked; nothing pruned; `Retry-After` a day off; and sign-in put back in
  a transaction, which silently stopped every failure from counting.
- Live, on the compose stack, through the gateway (the new CI step, run locally): five wrong guesses at
  one account, each with a different forged `X-Forwarded-For` entry, then the right password got 429
  with `Retry-After: 900`. That shows the gateway appends its own entry and that only it counts: had the
  forged entries counted, the five guesses would have looked like five clients and nothing would have
  been throttled. The password-reset step that follows in CI still passed, and every stored key was a
  64-character hash.

## Consequences

**Good.** A guess now costs a slot in a small, shared budget, not just a hash's worth of time, and
stuffing a leaked password list through one machine stops after thirty tries.

**Accepted.**

- Concurrent attempts can overshoot a limit slightly. The check and the record are not atomic, so a burst
  that all arrives before any failure is written gets through together. The overshoot is bounded by the
  burst, and the limit holds from then on.
- An attacker failing on purpose from fifty machines can keep a real owner out of sign-in for an hour.
  Password reset is the way back while that holds.
- The published service port lets a caller write their own client address. That is the stack's general
  exposure, and the per-account limit is unaffected.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- A password reset or an account closure does not end sessions already signed in ([ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md)).
