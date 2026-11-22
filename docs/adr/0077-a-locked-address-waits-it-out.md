# 0077 — A locked address waits it out

**Status:** Accepted · **Date:** 2026-11-22 · **Phase:** post-roadmap · **Answers an open item of [ADR 0072](0072-an-address-earns-its-typos.md)**

## Context

ADR 0072 credited an address for the established accounts that sign in from it, so that many honest shoppers behind
one office or carrier address are not locked out by each other's typos. It left one case open. The per-client limit is
checked before the password, and while it holds even a right password is refused ([ADR 0058](0058-a-guess-costs-more-than-a-hash.md)),
so a locked address records no successes and cannot earn the credit that would unlock it. ADR 0072's load runs never
reached the limit, because successes outpaced typos. The question was whether an honest address can be locked in a
way it cannot recover from.

`load/sign-in.sh` now takes two more settings, so the order of failures and successes can be chosen rather than left
to timing:

- a third argument, the seconds by which every mistyped attempt goes ahead of every correct one;
- `TYPISTS_GIVE_UP=1`, under which those who mistype give up rather than type it again correctly.

Each refused shopper's `Retry-After` is recorded too, which says what they were told about how long the address stays
locked.

## What was measured

1,000 shoppers from one address, 5% of whom mistype first, with the default limit of 30 failures per 15 minutes:

| Order of attempts | Right password refused | Told to wait (`Retry-After`) |
|---|---|---|
| together (ADR 0072's case) | 0 | none |
| typos 3 s ahead, and the typists then type it right | 23 | **900 s** |
| typos 3 s ahead, and the typists give up | **950, every one** | **897–898 s** |

**When the typists come back, it heals itself.** Their 50 failures closed the limit. The 27 who had typed it right
before it closed had already earned 27 accounts' credit, which lifted the limit above 50 within seconds. Everyone who
arrived three seconds later got in. Only the 23 whose correct attempt landed in that moment were refused.

**When nobody succeeds first, it does not.** 50 failures and no successes is a lock that only time lifts. All 950
shoppers with the right password were refused for 15 minutes, because a refused attempt records no success, so no
credit can accrue. 50 failed accounts and no successful ones from one address is exactly credential stuffing's
signature. Here it was honest shoppers who forgot their passwords.

**Either way, a refused shopper is told to wait the worst case.** `Retry-After` assumes the credit will not change, so
it gives the full fifteen minutes even when the lock lifts seconds later, as it did in the second row.

## Decision

Change nothing, and record it.

Three ways out were weighed:

- **Credit from live sessions.** A refresh from the address would earn credit, so shoppers already signed in would
  unlock it. It costs a stuffer nothing, but it helps only where somebody at the address is already signed in.
- **A shorter `Retry-After`.** Capping the hint so a refused shopper tries again soon is no security change, but on its
  own it unlocks nothing.
- **A trickle of evaluated attempts while locked.** Evaluating, say, one attempt every five seconds would let a right
  password through to earn credit, and lift the lock above in about two minutes. A stuffer would then get those guesses
  too: about 180 per address per 15 minutes, against 30 today.

None was chosen. The hard lock needs dozens of failures from one address with almost no successes before the rest of
its traffic arrives. That is rare for honest traffic and is the pattern the per-client limit exists to stop.

A password reset is no way out either, and that is worth knowing before anyone suggests one to a locked shopper.
Confirming a reset returns no session, and `clearAfterReset` ([ADR 0062](0062-the-gaps-between-the-pieces.md)) clears
only that account's own counts. Signing in from the locked address is still refused until the limit lifts.

## Consequences

- An address that records many failures and almost no successes is locked for up to 15 minutes, honest or not. If the
  first shoppers to mistype keep trying, the lock lifts as soon as a few of them succeed.
- `Retry-After` overstates the wait whenever credit lifts the limit sooner. A client that obeys it waits up to 15
  minutes for a lock that may last seconds.
- If either starts to matter, the three options above are costed, and the harness can reproduce both cases:
  `./load/sign-in.sh 1000 5 3` and `TYPISTS_GIVE_UP=1 ./load/sign-in.sh 1000 5 3`.
