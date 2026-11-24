# 0079 — A stolen token cannot sign you out

**Status:** Accepted · **Date:** 2026-11-24 · **Phase:** post-roadmap · **Closes a gap named in [ADR 0062](0062-the-gaps-between-the-pieces.md)**

## Context

`POST /api/v1/users/me/sign-out-everywhere` ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md))
revoked every refresh token of the account on the strength of an access token alone. An access token is
exactly what leaks: from a log, a proxy, a browser extension, a shared machine. Whoever held one could sign
the owner out of every device, and again each time the owner signed back in, for the fifteen minutes the
token lasted. Nothing was taken over, but the owner could not shop, and could not stop it.

[ADR 0062](0062-the-gaps-between-the-pieces.md) named this and left it, as a nuisance rather than a
takeover. Its other two open items have since been closed:
[ADR 0063](0063-changing-a-password-you-know.md) added changing a password, and
[ADR 0065](0065-two-tabs-are-not-a-thief.md) gave two racing tabs a grace window. This was the one left.

## Decision

Signing out everywhere now asks for the current password as well as the token, as closing an account and
changing the password already do:

```bash
curl -X POST localhost:8080/api/v1/users/me/sign-out-everywhere \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"password":"<the current password>"}'
```

- **A wrong password** gets 401 `INVALID_CREDENTIALS`, and nothing is revoked.
- **No password**, including no body at all as every caller sent before, gets 400.
- **The password is checked under the sign-in throttle, with the same counts**
  ([ADR 0058](0058-a-guess-costs-more-than-a-hash.md)). Otherwise a stolen token would become a way to guess
  the password at full speed, which is the trap closing an account fell into
  ([ADR 0062](0062-the-gaps-between-the-pieces.md)). While the throttle holds, even the right password gets
  429. Failures here hold sign-in back too, and a wrong password's failure is not rolled back with the
  refusal.
- **A success** clears this client's count for the account, as a successful sign-in does.

### What the owner sees

Nothing new: no email. Signing out everywhere takes nothing from anyone, and a thief who knows the password
has better things to do with it. Changing the password, which is the takeover, already emails the owner
([ADR 0063](0063-changing-a-password-you-know.md)).

## Considered and not chosen

- **Leaving it, and making the owner's remedy faster.** A shorter access token would shorten the nuisance
  without ending it, and every service would pay for it in refreshes.
- **Asking for the refresh token instead of the password.** It would show the caller holds a session, not
  only a copied access token. But it leaks from the same places, a client that has lost it could not sign
  out anything, and no other path asks for it this way.

## Consequences

**Good.** A copied access token can read and change what it always could for fifteen minutes, but can no
longer keep its owner signed out. No path that checks a password escapes the throttle.

**Accepted.**

- **Clients must send the password.** Any client calling this endpoint without a body now gets 400. Within
  this repository, only the CI's gateway check called it, and it now sends the password.
- **An owner who has forgotten the password** cannot sign out everywhere this way. A password reset signs
  out everywhere too ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md)) and needs only the mailbox.
- **An owner whose address the throttle holds** gets 429 here, as for sign-in. The reset is the way out
  again: it ends every session, and it lifts the account-wide count for the client that confirms it
  ([ADR 0062](0062-the-gaps-between-the-pieces.md)). An address held by its own client count still waits it
  out ([ADR 0077](0077-a-locked-address-waits-it-out.md)).

**Still open.** Nothing from [ADR 0062](0062-the-gaps-between-the-pieces.md)'s list.

## Verified

- New tests:
  - `SessionsIT`: with only the access token, a wrong password gets 401 `INVALID_CREDENTIALS`, and no body or
    an empty password gets 400. No refresh token is revoked on the record, and both devices still refresh.
    With the password, every session ends, as before.
  - `SignInThrottleIT`: three wrong passwords, then the right one gets 429 with nothing revoked, and sign-in
    from that client is held back too.
- Mutations, all 4 caught: the password not checked; the throttle not consulted; a wrong password not
  counted; that failure rolled back with the refusal.
- Live, through the gateway on the compose stack: a token alone with no body got 400, and with a wrong
  password got 401. The other device then still refreshed (200). With the right password, 204, and the other
  device's next refresh got 401. On a second account, five wrong passwords got 401 each. The right one then
  got 429 with `TOO_MANY_ATTEMPTS`, sign-in from the same client got 429, and its session was still live.
- The CI's gateway check now sends a wrong password (expects 401), then the right one (expects 204).
