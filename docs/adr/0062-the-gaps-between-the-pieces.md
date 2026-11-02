# 0062 — The gaps between the pieces

**Status:** Accepted · **Date:** 2026-11-02 · **Phase:** post-roadmap · **Amends [ADR 0053](0053-closing-an-account.md), [ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md) and [ADR 0058](0058-a-guess-costs-more-than-a-hash.md)**

## Context

Thirteen security and privacy changes landed in a row: support's reads, email retention, account closure,
password reset, two throttles, sessions, and alerts (ADRs 0050–0061). Each was tested on its own terms.
None was tested against the others. So the work was reviewed as a whole, reading the code on `main` and
asking, where two features meet, whether either undoes or sidesteps the other.

It found four things worth fixing now. Two were reproduced against `main` before being fixed, with a
throwaway test that was not kept:

- after a successful password reset, the owner signing in with the **new** password got 429;
- an address registered as another account's future closure placeholder made that account's closure fail
  with a 500.

## Decision

### 1. A password reset lifts the account-wide lockout

[ADR 0058](0058-a-guess-costs-more-than-a-hash.md) named the reset as the owner's way back while an
attacker, failing from many places, held the per-account sign-in limit. But that limit counts by address,
not by password, and the reset only ended sessions ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md)).
So the owner reset their password and was still refused for up to an hour.

A confirmed reset now deletes the address's account-wide count, and its count for the client that
confirmed the reset, which is almost always the owner's own device. Only someone who can read the
address's mail can confirm a reset, so this cannot be used to clear an attack's count. Other clients'
counts for the address cannot be found, because each is hashed with its client, and they hold back only
those clients. If the attack goes on, the account limit fills again from zero.

### 2. Closing an account checks the password under the sign-in throttle

Closing an account ([ADR 0053](0053-closing-an-account.md)) asked for the password and compared it with no
limit at all: a way to guess a password that went round [ADR 0058](0058-a-guess-costs-more-than-a-hash.md)
entirely. The harm was bounded, because it needed a live session and a correct guess closes the account
rather than handing it over, but a password check that is unthrottled is the way round the throttled ones.

It now uses the same throttle and the same counts as sign-in. While the throttle holds, even the right
password is refused, and failures here hold sign-in back too, so neither path is a way round the other.
Closing runs in a transaction. A wrong password must not roll back the failure it records, which is the
trap sign-in fell into in ADR 0058, so the transaction does not roll back on that refusal. Nothing else is
written before that point.

### 3. The owner is told their password changed

A reset is how an account is recovered, and also how it is taken over, and it was silent. Now a confirmed
reset queues an email to the account, "Your FlashCart password was changed", through the email queue
([ADR 0046](0046-the-first-message-out.md)). It says that every device was signed out, and what to do if
it was not them: reset again, and secure the mailbox, since whoever did this could read the link.

### 4. Nobody registers at `.invalid`

Closing an account moves its email to `closed-<id>@closed.invalid`, a domain reserved never to receive mail
(RFC 2606). Anyone could register that exact address in advance, given the account's id. The closure's
update then collided with it on the unique constraint and failed. Registration now refuses any address at
`.invalid` with 400 `EMAIL_UNDELIVERABLE`. The database refuses it too, for an `UPDATE` typed by hand: only a
closed account may hold one.

## Considered and not changed

- **Two tabs refreshing at once sign the user out.** Reuse of a refresh token is treated as theft
  ([ADR 0059](0059-a-session-ends-within-fifteen-minutes.md)), and two tabs presenting the same token look
  exactly like it, which also raises `RefreshTokenReused`. A grace window of a few seconds would avoid that
  at some cost to detection. Left as it is, and named here.
- **A stolen access token can sign the owner out everywhere.** A nuisance for up to fifteen minutes, not a
  takeover. Requiring the password would close it. Left as it is.
- **A signed-in user cannot change their password**; only a reset changes it. A gap in features, not a
  flaw between them.

## Checked and found sound

- Reset links stop working for closed and disabled accounts.
- Closing deletes the account's stored emails and refuses new ones.
- A refresh re-reads the account's status and roles.
- The reset token is never at rest.
- Throttle keys are hashed.
- Reset, sign-in and both throttles answer an address with no account exactly as one with an account.

## Verified

- New tests:
  - `SignInThrottleIT`: a reset lets the owner back in, from their own client and from another, while the
    account limit held; closing is throttled, refused even with the right password, the account stays open,
    and those failures also hold sign-in back;
  - `PasswordResetIT`: the owner receives "Your FlashCart password was changed" after a reset;
  - `UserIT`: `.invalid` addresses are refused at registration in any case, and by the database for a
    hand-typed update.
- Mutations, all 8 caught: a reset leaving the lockout; a reset clearing only the confirming client's
  count; closing not checked; closing's failures not counted; closing rolling its failures back; the
  owner not told; `.invalid` accepted at registration; `.invalid` allowed by the database.
- Live, on the compose stack: registering at `closed.invalid` got 400. With one account's limit held by
  50 failures from 50 clients, the owner's right password got 429. After a reset through the real email,
  their new password signed in (200), the case that got 429 on `main`, and "Your FlashCart password was
  changed" arrived in Mailpit. Five wrong passwords to close an account got 401 each, and the right one
  then got 429, with the account still `ACTIVE`.

## Consequences

**Good.** A reset is now the way back it was described as. No path checks a password without the
throttle, a reset that was not the owner's is visible to them, and closing an account cannot be blocked
from outside.

**Accepted.** A reset clears the counts only for the client that confirms it. An owner who resets on their
phone and signs in on a laptop the attacker's clients never touched is unaffected. One who signs in from a
client the attacker also used waits out that client's count.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- The three items under *Considered and not changed*.
