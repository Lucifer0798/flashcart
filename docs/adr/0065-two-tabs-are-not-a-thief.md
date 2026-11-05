# 0065 — Two tabs are not a thief

**Status:** Accepted · **Date:** 2026-11-05 · **Phase:** post-roadmap · **Amends [ADR 0059](0059-a-session-ends-within-fifteen-minutes.md); closes an item named in [ADR 0062](0062-the-gaps-between-the-pieces.md)**

## Context

[ADR 0059](0059-a-session-ends-within-fifteen-minutes.md) made every refresh token single-use, and treated
a used token coming back as theft: the whole session ends, and
[ADR 0061](0061-the-first-theft-is-the-one-that-counts.md) raises `RefreshTokenReused`. The reasoning was
that the client that rotated a token would never present it again.

A browser with two tabs does exactly that. Both tabs hold the same refresh token, both see the access token
expire, and both refresh at once. One wins. The other presents a token used a moment earlier, which looked
like theft, so a shopper with two tabs open was signed out, and the theft alert fired for nothing. An alert
that fires for having two tabs open teaches people to ignore the alert that matters.

## Decision

A token presented again **within ten seconds** of being used (`flashcart.security.refresh.reuse-grace`) is
refused with the same 401, but nothing else happens: the session stands, nothing is counted, and no alert
fires. The losing tab gets no token. It re-reads the new one its sibling stored, and carries on.

Past ten seconds the behaviour is as it was: the session ends, and the reuse is counted. The window is
measured against `used_at`, in the database, on the clock that set it.

Ten seconds is long enough to cover tabs waking together and a slow request, and short enough that a real
copy is unlikely to be used inside it.

## What this gives up

The order of events matters for a stolen token. If the thief refreshes first and the owner's client presents
the same token within ten seconds, that presentation is not flagged, and the thief holds the live session.
It is caught the first time the owner's client presents that token again after the window, which ends the
session. The opposite order was already safe: the owner refreshes first, and the thief's copy is refused,
inside the window or out of it.

A client that gets a 401 and gives up without retrying would not reveal the thief. The session then runs
until the thief's next rotation is contested, and at the latest ends with the next password change, reset or
sign-out-everywhere.

That is accepted. False alarms at every multi-tab refresh were certain and frequent. This case needs a stolen
token, a race won inside ten seconds, and a client that never presents its token again.

## Verified

- `SessionsIT`, 2 new tests and 1 changed, which check that:
  - a token reused at once is refused, nothing is counted, and the session's next token still works;
  - a token reused after the window (its `used_at` aged by a minute) is refused, counted once, and ends the
    session, so the next token no longer works.
- Mutations, all 3 caught: no grace, so a race ends the session; grace for ever, so theft is never
  caught; a race counted as theft while the session stands.
- Live, on the compose stack, through the gateway (CI's session step, run locally): a reuse at once got
  401 and the session's next token still worked; after waiting out the window, the same reuse got 401
  and ended the session. `flashcart_user_refresh_reused_total` went from 0 to exactly 1, so the race was
  not counted and the real reuse was.
- CI's session step now checks both sides through the gateway: a reuse at once leaves the session working,
  and the same reuse after waiting out the window ends it.

## Consequences

**Good.** Two tabs no longer sign a shopper out, and `RefreshTokenReused` means what it says.

**Accepted.** The narrow case above, and an extra eleven seconds in CI's session step to wait out the window.

**Still open.**

- A stolen access token can sign the owner out everywhere ([ADR 0062](0062-the-gaps-between-the-pieces.md)).
