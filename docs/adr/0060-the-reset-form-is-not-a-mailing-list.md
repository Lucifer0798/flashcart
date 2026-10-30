# 0060 — The reset form is not a mailing list

**Status:** Accepted · **Date:** 2026-10-30 · **Phase:** post-roadmap · **Builds on [ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md) and [ADR 0058](0058-a-guess-costs-more-than-a-hash.md)**

## Context

[ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md) limited reset requests per account: a
second request for the same address within a minute sends nothing more. Nothing limited them per client.
One machine could ask for resets for a list of a thousand addresses, a minute apart or all at once, and
the platform would email every one of them that has an account. That spams people who asked for nothing,
in the platform's name, and costs the mail sender the reputation every other email depends on.

## Decision

### A fourth limit, on reset requests per client

**10 requests an hour from one client**, by default. The eleventh gets 429 `TOO_MANY_ATTEMPTS` with
`Retry-After`. Ten is far more than anyone who has forgotten a password needs, and far fewer than a list
needs. The client is the gateway's own `X-Forwarded-For` entry, as for sign-in
([ADR 0058](0058-a-guess-costs-more-than-a-hash.md)).

### Every request counts, and the refusal depends only on the client

Requests are counted before the address is looked up, whether or not it has an account. Counting only the
requests that send an email would make the limit arrive sooner for a list full of real addresses, and the
point at which 429 arrives would say how many of them have accounts. That is the question ADR 0057 was
built not to answer. Refused requests are not counted, so being refused does not extend the wait.

The 429 is the same for any address. It names nothing, and it arrives because of what this client has
asked before, never because of what it is asking now.

### One table, now named for what it holds

The limit lives in the same throttle and table as sign-in's three. The table counted only failed sign-ins
and was named `sign_in_failures`. It now counts two kinds of attempt, so it is renamed `throttled_attempts`,
and the class `AttemptThrottle`. Keys stay hashes, with their own prefix per limit. Pruning keeps the longest
window of all four, so a sign-in window shorter than the reset window cannot delete requests that still
count.

## Verified

- `SignInThrottleIT`, 4 new tests at a limit of 3 and a two-hour window, which check that:
  - three requests from one client, for addresses with and without accounts, are accepted; the fourth is
    refused identically for an address with an account and one without, with `Retry-After` within the
    window, while another client is unaffected;
  - requests for addresses with no account count too, and a refused request records no reset;
  - reset requests and failed sign-ins are counted apart, in both directions: three of each from one
    client, which together would reach the sign-in client limit, are all still accepted;
  - pruning keeps reset requests for their own window, which in the test is longer than every sign-in
    window.
- `PasswordResetIT` makes more requests from one client than the limit allows, so it sets the limit out of
  the way. Its own subject is the reset, not the limit.
- Mutations, all 6 caught: reset requests not checked; not counted; counted only for addresses with
  accounts; counted under the sign-in client's key; pruning by the sign-in windows alone; `Retry-After`
  a day off. The last two of those cases were added before the run, once it was clear that the first
  version of the separation test, and equal one-hour windows, could not tell them apart.
- Live, on the compose stack, against its existing database: V13 renamed the table in place. CI's reset
  step still passed, and the new step was then refused after eight more requests, ten in all from one
  client, with `Retry-After: 3595`.

## Consequences

**Good.** The reset form can no longer be used to mail a list, and the limit gives away no more about who
has an account than the form already did.

**Accepted.**

- Many people behind one address, such as an office or a carrier's NAT, share ten requests an hour.
- In CI every request reaches the user service as one client, so the step that checks this limit spends
  the budget, and has to run after the step that tests a real reset.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
