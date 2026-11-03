# 0063 — Changing a password you know

**Status:** Accepted · **Date:** 2026-11-03 · **Phase:** post-roadmap · **Closes a gap named in [ADR 0062](0062-the-gaps-between-the-pieces.md)**

## Context

A password could be changed only by resetting it through email
([ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md)). A shopper who knew their password and
wanted a new one, because it was reused elsewhere or because they suspected someone else had it, had to
pretend to have forgotten it. [ADR 0062](0062-the-gaps-between-the-pieces.md) named this as a gap.

## Decision

`POST /api/v1/users/me/password`, signed in, with `currentPassword` and `newPassword`.

- **The current password as well as the token.** A stolen session must not be able to lock its owner out by
  changing the password under them. The new password follows registration's rule: length only, 12 to 200.
  The same password again is refused with 400 `PASSWORD_UNCHANGED`.
- **Under the sign-in throttle.** The current password is checked with the same throttle and the same counts
  as sign-in ([ADR 0058](0058-a-guess-costs-more-than-a-hash.md)), as closing an account is since
  [ADR 0062](0062-the-gaps-between-the-pieces.md). Otherwise it would be a way to guess a password from a
  stolen session at full speed. While the throttle holds, even the right password is refused. A wrong one
  is recorded, and the transaction is told not to roll that record back with the 401.
- **Every session ends, and the caller gets a new one.** Whoever else had the old password may have a
  session, so every refresh token is revoked ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md)).
  That includes the caller's own, because an access token does not say which refresh token belongs to this
  caller. The response is a fresh session, so the caller carries on without signing in again, and every
  other device is signed out within fifteen minutes.
- **The owner is told.** The same "Your FlashCart password was changed" email as a reset, worded for this
  case: changed by someone signed in to the account, and, if it was not them, to reset now, because whoever
  did it knew the old password. The reset's own email now says explicitly that it came from a reset.

## Verified

- `PasswordResetIT`, 3 new tests, which check that:
  - a change succeeds; the new password signs in and the old does not; two earlier sessions, the caller's
    included, can no longer refresh, while the returned one can; the owner receives the signed-in wording;
  - a wrong current password (401 `INVALID_CREDENTIALS`), the same password (400 `PASSWORD_UNCHANGED`), a
    short one, and no token each change nothing and send nothing;
  - five wrong current passwords from one client throttle the sixth, even when right, and leave the
    password unchanged.
- Mutations, all 9 caught: the current password not checked; not throttled; failures not counted;
  failures rolled back; old sessions surviving; the owner not told; the reset wording sent instead; the
  same password accepted; the caller given no new session.
- Live, on the compose stack, through the gateway (the new CI step, run locally): the change returned
  200 with a new session. The old refresh token was then refused and the new one accepted, and the new
  password signed in while the old one did not. The email that reached Mailpit carried the signed-in
  wording.

## Consequences

**Good.** A password can be changed without pretending to have forgotten it, and changing it ends whatever
sessions the old one allowed.

**Accepted.** The caller's own refresh token ends too and is replaced in the response. A client that ignores
the response keeps its access token for up to fifteen minutes, then has to sign in again.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- Two tabs refreshing at once sign the user out, and a stolen access token can sign the owner out
  everywhere ([ADR 0062](0062-the-gaps-between-the-pieces.md)).
