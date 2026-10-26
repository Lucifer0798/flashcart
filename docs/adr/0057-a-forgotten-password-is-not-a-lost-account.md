# 0057 — A forgotten password is not a lost account

**Status:** Accepted · **Date:** 2026-10-26 · **Phase:** post-roadmap · **Builds on [ADR 0046](0046-the-first-message-out.md), [ADR 0048](0048-the-order-tells-its-own-story.md) and [ADR 0051](0051-support-can-see-what-we-told-them.md)**

## Context

There was no way back into an account whose password had been forgotten. Sign-in refuses without saying
why, deliberately, and there was nothing else. Every order, waitlist place and refund email stayed tied to
an account its owner could not open.

[ADR 0046](0046-the-first-message-out.md) and [ADR 0048](0048-the-order-tells-its-own-story.md) built the
missing piece: a queue that delivers email reliably, retried through a mail outage and never sent stale.
A reset link is one more email.

## Decision

### Asking reveals nothing

`POST /api/v1/users/password-reset` with an email address answers **202 with the same body whatever
happened**:

- an address with an active account;
- an address with no account;
- a closed account;
- a request repeated too soon.

Sign-in was built not to say whether an address has an account. A reset form that said so would undo
that, letting anyone ask whether somebody shops here.

A second request for the same account within a minute sends nothing more, so the form cannot be used to
fill somebody's inbox.

### The token is never stored

The request records a reset (an id, the account, an expiry thirty minutes out) and queues an email whose
payload is **only the reset's id**. The token is minted by the sender, in the transaction that sends the
email. It is 32 bytes from `SecureRandom`, its SHA-256 is written to the reset, and the token itself goes
only into the email.

So nothing at rest opens the account:

- not the resets table, which holds a hash;
- not the email queue, which holds an id;
- not support's view of a customer's emails ([ADR 0051](0051-support-can-see-what-we-told-them.md)),
  which renders the reset email without a link and says the link is not kept.

Someone who can read the database could otherwise reset any password, which turns read access into
write access.

If the mail server refuses the send, the retry mints a fresh token, so only the token in the email that
actually went out works. A reset that has expired or been used by the time its email would go is
skipped as `STALE`, not sent. The notice's shelf life is the reset's own expiry, because a link that no
longer works is worse than no email.

### It works once, briefly, and for an active account only

`POST /api/v1/users/password-reset/confirm` takes the token and the new password, with the same length
rule as registration. The reset is claimed and marked used in one statement, which also requires the
account to be active. Two uses racing each other cannot both succeed, and a link mailed to an account
that has since closed does nothing.

Every way a token can be wrong is the same 400 `RESET_TOKEN_INVALID`: unknown, expired, used, or for a
closed account. A new password too short for the rules is refused before the token is touched, so the
link is not spent on a password that was never set. Using a link cancels every other reset still
outstanding for the account.

Spent and expired resets are deleted a day after they expire.

## Verified

- `PasswordResetIT`, 8 tests, which check that:
  - a link sets a new password once, after which the old password fails and the new one signs in;
  - an unknown address gets an identical response and no email;
  - the token appears nowhere at rest: the hash differs from it, and neither the queue's payload nor
    support's rendering of the email contains it;
  - an expired link fails, and an email whose reset expired before it could be sent is never sent;
  - three requests in a minute send one email;
  - a refused send is retried with a fresh token, and the delivered one works;
  - using one link cancels the others;
  - a disabled account gets no email. It keeps its address, so this tests the status check: a closed
    account's address has already changed, which would pass for the wrong reason;
  - a link already in the inbox stops working once its account closes;
  - a made-up token is refused, and a too-short password does not spend the link.
- Mutations, all 9 caught: the token stored raw; an unknown address answered differently; no limit on
  repeats; an expired link accepted; a used link accepted again; other links left working; an expired
  reset still emailed; an inactive account sent a reset; a closed account's link still working. The last
  two are caught only by the refusal cases added once the original test was seen to pass for the wrong
  reason.
- Live, on the compose stack, through the real SMTP path into Mailpit (the new CI step, run locally):
  - a known and an unknown address got byte-identical 202 answers;
  - the token was read out of the delivered email, and it set the new password;
  - the old password then failed, and reusing the link got 400;
  - the reset row held a 64-character hash, and the sent notice's payload held only the reset's id.

  With Mailpit stopped, the step failed, because no email arrived.

## Consequences

**Good.** A shopper who forgets their password can get back in, without the reset becoming a way to learn
who has an account or a way for a database reader to take one over.

**Accepted.**

- Access tokens already issued are not revoked when the password changes. Every service verifies them
  for itself, so they stay valid until they expire, up to twelve hours. That is the same
  stateless-token limit [ADR 0053](0053-closing-an-account.md) met. Someone resetting because their
  account was taken over is not protected from a token the intruder already holds.
- The two paths through the request do different amounts of work: a lookup that finds nothing, against a
  lookup and two inserts. The difference is a few milliseconds of database time, far below what the
  mail server's latency would have exposed had the email been sent in the request. It is not equalised.

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- Nothing throttles sign-in attempts beyond BCrypt's cost.
