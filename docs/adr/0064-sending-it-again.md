# 0064 — Sending it again

**Status:** Accepted · **Date:** 2026-11-04 · **Phase:** post-roadmap · **Closes the item [ADR 0051](0051-support-can-see-what-we-told-them.md) left open**

## Context

Since [ADR 0051](0051-support-can-see-what-we-told-them.md), support can see every email a customer was
meant to get: sent, still being retried and why, or skipped and why. It could not act on any of it. "I
never got my refund email" could be confirmed and not mended; a sent email that went to spam, an order
email skipped as stale after a long mail outage, one stuck behind a refusing mail server could only be
explained. The item was carried as open through thirteen records.

Sending something again is not as simple as it sounds, because not every email stays true.

## Decision

### Only what is still true

| Email | Resent? | Why |
|---|---|---|
| order confirmed, dispatched, delivered, cancelled; refunded | always | each states a fact that stays true |
| your password was changed | always | the same |
| back in stock | only while its hold lasts | after that the unit has gone to the next in line ([ADR 0045](0045-told-means-yours.md)), and saying it is theirs would be false |
| reset link | never | the token is minted as the email is sent, and the request may be spent ([ADR 0057](0057-a-forgotten-password-is-not-a-lost-account.md)); the customer asks for a new one |
| anything, to a closed or disabled account | never | there is nobody to send it to |

The refusals are 409, `HOLD_LAPSED`, `NOT_RESENDABLE` and `NO_RECIPIENT`, each saying why.

### Retried in place, or queued as a copy

- **An email still waiting**, usually behind a mail server that refused it, is retried at once rather than
  at the end of its backoff. Its shelf life is extended, so a long outage does not skip it.
- **An email sent or skipped** is queued again as a new email, keyed from the original. The original keeps
  its history: when it went, or why it did not. The copy takes the original's `occurred_at`, so it keeps
  its place in its order's sequence ([ADR 0048](0048-the-order-tells-its-own-story.md)), and gets a fresh
  shelf life. The exception is a back-in-stock email, whose deadline is the hold's and stays so.

### Support only, recorded first, and visible to the customer

`POST /api/v1/user/_notices/{key}/resend`, for the `SUPPORT` capability, checked by the filter and again in
the handler, as every staff action is. Support's view of a customer's emails now carries each one's `key`,
so the email can be named.

The resend is recorded in the user service's access log, as `RESEND_NOTICE`, before anything is sent, as
every staff action on a customer's data is ([ADR 0025](0025-record-what-an-operator-reads.md)). It is
recorded whether or not the rules then allow it. The customer sees it in their own access log
([ADR 0056](0056-a-customer-sees-that-staff-looked.md)) as "A member of staff sent one of our emails to you
again".

## Verified

- `NoticeIT`, 6 new tests, which check that:
  - a sent order email is queued again as a copy and arrives, and both rows are `SENT`;
  - an email stuck behind a refusing mail server is retried at once, in place, without a second row;
  - an order email skipped as stale goes again;
  - a back-in-stock email goes again while held, and is refused `HOLD_LAPSED` once lapsed;
  - a reset link is refused `NOT_RESENDABLE`, an email to a disabled account `NO_RECIPIENT`, and an
    unknown key gets 404;
  - a shopper or the warehouse gets 403, and the handler refuses a customer on its own when called directly;
    support's resend is recorded with the key, and the customer's own log describes it in words.
- Mutations, all 9 caught: a reset link resent; a closed account mailed; a lapsed hold resent; the copy
  keeping a lapsed shelf life; a stuck email waiting out its backoff; a sent email "retried" in place,
  which sends nothing; the resend not recorded; the customer not told in words; the handler's role check
  removed. The last survived the first run, because over HTTP the filter refuses first, until the direct
  call was added.
- Live, on the compose stack, through the gateway (CI's outside-refund step, run locally): support
  listed the shopper's three emails, took the refund email's key, and resent it. The answer was 202
  `QUEUED`, with a new key, and a second copy of "Refund for order" arrived in Mailpit.

## Consequences

**Good.** "I never got it" now has an answer that ends with the customer getting it, and the platform still
never sends something that has stopped being true.

**Accepted.** Nothing limits how many times support resends one email. Each resend is recorded and visible to
the customer, which is the check on it.

**Still open.**

- Two tabs refreshing at once sign the user out, and a stolen access token can sign the owner out
  everywhere ([ADR 0062](0062-the-gaps-between-the-pieces.md)).
