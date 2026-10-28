# 0059 — A session ends within fifteen minutes

**Status:** Accepted · **Date:** 2026-10-28 · **Phase:** post-roadmap · **Closes a gap accepted by [ADR 0053](0053-closing-an-account.md) and [ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md)**

## Context

Every service verifies an access token for itself ([ADR 0021](0021-the-client-does-not-say-who-it-is.md)).
That is what keeps the platform from depending on the user service for every request. It also means a
token cannot be withdrawn once issued. Tokens lasted twelve hours, so:

- a password reset left whoever had the old password signed in for up to twelve hours
  ([ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md));
- a closed account's tokens kept working, which order and inventory had to refuse one by one
  ([ADR 0053](0053-closing-an-account.md), [ADR 0054](0054-a-closed-account-cannot-queue.md));
- a staff role revoked with `grant-role.sh` stayed in every token already issued;
- a shopper had no way to sign out of a lost phone at all.

Two ways to fix it were weighed. One broadcasts "tokens issued before now are void" to all seven
services, each of which records it and refuses older tokens. That takes effect in seconds, but every
service gains a consumer, a store and a check, and one that is down or lagging misses the revocation
until it catches up. The other keeps tokens short and makes the means of getting the next one revocable,
all inside the user service. That is the established answer, and the one taken.

## Decision

### Access tokens last fifteen minutes; a refresh token keeps the session going

Sign-in now returns two tokens:

- an **access token** for fifteen minutes, sent on every request exactly as before;
- a **refresh token**: 32 random bytes, kept in the user service only as its SHA-256, valid for thirty
  days from its last use.

`POST /api/v1/users/sessions/refresh` exchanges a refresh token for a new pair. No other service changes.
Fifteen minutes is now the longest any session outlives being ended.

### A refresh token works once, and a second use ends the session

Exchanging a refresh token marks it used in the same statement that checks it, so two exchanges racing
each other cannot both succeed. It comes back replaced by a new one in the same **family**: one sign-in
and everything rotated from it.

A used token presented again means somebody else holds a copy, because the client that rotated it would
never send it twice. The whole family is revoked: the thief's copy and the owner's current token alike.
The owner signs in again, and the thief has nothing. This is counted, as
`flashcart_user_refresh_reused_total`.

### The account is read again on every refresh

A refresh reloads the account rather than copying the old token's claims. So a role granted or revoked
with `grant-role.sh` ([ADR 0047](0047-staff-roles-on-the-record.md)) reaches the next access token, and a
closed or disabled account cannot refresh at all. Before, a revoked role lasted twelve hours. It now
lasts at most fifteen minutes.

### Ending sessions

- `POST /api/v1/users/sessions/sign-out` with a refresh token ends that session: one device. It always
  answers 204, whether or not the token was known.
- `POST /api/v1/users/me/sign-out-everywhere`, signed in, revokes every refresh token of the account,
  including the caller's own.
- A **password reset** and **closing an account** do the same, in the transaction that changes the
  password or erases the identity.

Every way a refresh can fail is the same 401: unknown, used, revoked, expired, or for an inactive
account. The refresh and sign-out paths are public in the user service's filter, because the refresh
token in the body is the credential, and the gateway already passes `/api/v1/users` through.

Spent refresh tokens are deleted a day after they expire.

### What else had to change

The chaos harness signed in once per run. An `all` run can outlast fifteen minutes, which would turn its
later scenarios into 401s that read as a platform fault, so it now signs in again before each scenario.
The load harness and CI's end-to-end job finish well inside one token's lifetime.

## Verified

- `SessionsIT`, 8 tests, which check that:
  - sign-in returns a 900-second access token (`exp − iat`) and a 30-day refresh token, stored only as a
    hash;
  - a refresh rotates the token, and the original presented again is refused and ends the rotated one
    too;
  - signing out ends one session and not another, and is silent about unknown tokens;
  - signing out everywhere ends every session, the caller's included, while a fresh sign-in still works;
  - a password reset ends a session held by whoever had the old password;
  - closing the account ends its sessions, and a disabled account cannot refresh;
  - a role granted and then revoked reaches the next access token each time;
  - an expired or made-up refresh token is refused.
- Mutations, all 10 caught: a used token working again; reuse not caught; signing out everywhere
  revoking nothing; a reset leaving sessions alone; closing revoking nothing; the account not read again
  on refresh; signing out of one session ending them all; expiry ignored; tokens lasting twelve hours
  again; the refresh token stored raw. "Closing revokes nothing" survived the first run: the refresh
  still failed, because the account check refuses a closed account anyway. The test now also asserts
  that the closure revoked the tokens on the record.
- Live, on the compose stack, through the gateway (the new CI step, run locally): sign-in gave a
  900-second token; a refresh returned a new pair whose access token reached `/me`; the original refresh
  token was then refused with 401; signing out everywhere gave 204, and the session's refresh token was
  refused. The database showed three refresh tokens, one used and all three revoked. The chaos harness's
  `catalog` scenario passed with its new per-scenario sign-in.

## Consequences

**Good.** A session can be ended: on one device, everywhere, by a password reset, by closing the
account, and by losing a role. No other service had to learn anything new.

**Accepted.**

- Up to fifteen minutes. An access token issued before a session ended still works until it expires.
  Immediate revocation would need the broadcast, and the window is now short enough not to.
- Clients must refresh. Anything holding a token for longer than fifteen minutes, as the chaos harness
  did, has to sign in or refresh again.
- Order's and inventory's closed-account tables stay. They refuse a closed account's checkout and
  waitlist joins within seconds, not fifteen minutes, which still matters for those two actions.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
