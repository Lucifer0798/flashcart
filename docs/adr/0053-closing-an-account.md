# 0053 — Closing an account

**Status:** Accepted · **Date:** 2026-10-24 · **Phase:** post-roadmap · **Amended by [ADR 0054](0054-a-closed-account-cannot-queue.md)** · **Builds on [ADR 0021](0021-the-client-does-not-say-who-it-is.md), [ADR 0045](0045-told-means-yours.md), [ADR 0047](0047-staff-roles-on-the-record.md) and [ADR 0052](0052-forgetting-an-email-safely.md)**

## Context

A customer could register and never leave. There was no way to close an account, and nothing ever
deleted a user: the email, name, password hash and addresses stayed for good, along with the emails kept
about them ([ADR 0052](0052-forgetting-an-email-safely.md) bounds those to ninety days, but only for an
account that stays open).

Surveying every schema showed something that made closure tractable. **Only the user service holds
anything that identifies a person.** Order, payment, inventory and shipping refer to a customer only by
`customer_id`, the user's UUID. No other service stores a name, an email or an address; a parcel has a
tracking number and nothing to deliver it to.

## Decision

### Erase the identity, keep the records

`POST /api/v1/users/me/close`, with the current password, closes the caller's account. The user's row is
kept, because its id is what every other service's records point at, and a row with that id is what
tells a later reader the account existed and was closed. Everything on it that identified a person is
overwritten:

- the email becomes `closed-<id>@closed.invalid`. `.invalid` is reserved never to resolve (RFC 2606),
  so nothing can be sent there, and the real address is free to register again as a new account;
- the name becomes "Closed account";
- the password becomes a value no encoder produces, so it matches nothing;
- the addresses are deleted;
- the account's notices, which name its orders and refunds, are deleted.

Orders, payments, shipments, reservations and the ledger are **kept**. They are financial and
operational records: an accountant, a chargeback or a stock count needs them. With the identity gone
they describe what happened, not who it happened to. Deleting them everywhere was the alternative, and it
would destroy exactly what those questions need, and break history such as stock movements that point at
the orders.

The database enforces the shape. A `CLOSED` row must have `closed_at`, the placeholder email and no
roles, so a closure typed by hand that forgets a column is refused.

### The password is asked for again

A token alone, copied from a log or a shared machine, cannot destroy an account. The wrong password is
the sign-in's answer, 401 `INVALID_CREDENTIALS`, and closes nothing.

### Staff accounts may close; their roles are revoked on the record

A staff account can be closed like any other. Its roles are cleared, and each one gets a `REVOKE` row in
`role_grants` with the reason "account closed by its holder", so the history of who held a role
([ADR 0047](0047-staff-roles-on-the-record.md)) records the end rather than stopping. Closing the last
operator is not prevented, and the ADR says so here rather than leaving it to be found.

### Leftover tokens: order refuses them, the rest do not need to

Every service verifies a token for itself ([ADR 0021](0021-the-client-does-not-say-who-it-is.md)), so a
token issued before closing stays valid for up to twelve hours. Closing publishes **`AccountClosed`** on a
new topic, `flashcart.user.events`, through the outbox and in the same transaction as the erasure, so no
service hears of a closure that rolled back.

- **The user service** answers 401 to the old token on `/me`, and records no new email for the account.
  The sender also refuses to mail a closed account's placeholder, for a notice already queued.
- **The order service** records closed ids and refuses checkouts from them with 401. Buying is the one
  harmful thing a leftover token can still do. The check runs before the idempotency lookup, so
  replaying the key of an order placed before closing cannot get anything through either.
- **Inventory** takes the account out of every waitlist. Waiting places are cancelled, and a unit already
  held for it is released through the ledger, which offers it to the next in line
  ([ADR 0045](0045-told-means-yours.md)). Otherwise a closed account would keep being told about stock
  and holding units nobody would buy.
- **Payment and shipping** do nothing. Reading one's own pseudonymous records harms nobody, and those
  services' records are among what closing keeps.

The order refusal starts when order hears the event, typically within a second or two. A checkout in
that gap goes through, which is the same order the customer could have placed a moment before closing.

## Verified

- `AccountClosureIT`, 6 tests, which check that:
  - the identity is erased, the id and row are kept, the addresses are gone, and the email is nowhere,
    with `AccountClosed` in the outbox, keyed by the customer;
  - a wrong password closes nothing and publishes nothing, and a blank one is a 400;
  - afterwards, the old token gets 401 on `/me` and on a second close, the old password signs in to
    nothing, and the email registers again as a new account;
  - a staff account's two roles are revoked in `role_grants`;
  - its notices are deleted, a later event records nothing, and a notice slipped in regardless is
    skipped as `NO_RECIPIENT`, not sent;
  - a hand-typed half-closure is refused by the database.
- `OrderIT`: after `AccountClosed`, which was delivered twice, a checkout is 401, and so is a replay of
  an earlier checkout's key. The earlier order is still readable, and another customer still buys.
- `WaitlistIT`: a closed account's waiting place is cancelled, its held unit is released, and the next
  shopper is told and holds it. When the first SKU comes back, nobody is told about it on the closed
  account's behalf.
- Mutations, all 12 caught: the password not checked; the addresses kept; the roles revoked off the
  record; the notices kept; the closure not announced; the old token still reaching `/me`; a new notice
  recorded for a closed account; the sender mailing one; order ignoring the closure; order checking it
  only after the idempotency replay; the waiting places kept; the held unit kept. "Addresses kept"
  survived the first run. The test's own setup call had been refused with a 400, because Jackson 3
  rejects a missing primitive, so it asserted "no addresses" of an account that never had one. The
  test now checks that the address exists first.
- Live, on the compose stack, running the CI step: closing returned 204, the waitlist place went to
  `CANCELLED`, and the old token's checkout got 401. Order heard the closure 1.1 s after it happened.
  The step was then run again with the order service stopped. It failed, as it should, and on restart
  order caught up and recorded that closure 3 min 32 s after it happened. `learned_at - closed_at` is
  exactly that window, which is why the table keeps both.
- CI, end to end: a shopper joins a waitlist and fails to close with the wrong password. They then
  close, and their old token gets 401 on `/me`. The waitlist place becomes `CANCELLED` once inventory
  hears, and a checkout with the old token becomes 401 once order hears.

## Consequences

**Good.** A customer can leave, and what identified them leaves with them. The records the business must
keep stay whole and stop pointing at a person.

**Accepted.**

- Closing is irreversible. There is no grace period, and no undo.
- The order a customer has in flight carries on. A refund still reaches their card, but no email says
  so, because there is no one to send it to.
- A cancellation reason the customer typed is stored as they typed it, on the order and its history. It
  is free text and could contain anything, and closing leaves it.
- A leftover token can still join a waitlist in inventory until it expires. The place it makes is never
  cleaned up, because the closure was already processed. *Closed by [ADR 0054](0054-a-closed-account-cannot-queue.md).*

**Still open.**

- Support can see an email is stuck but cannot resend it ([ADR 0051](0051-support-can-see-what-we-told-them.md)).
- `compose.yaml` does not pass `TRACE_SAMPLING` to the services ([ADR 0049](0049-loose-ends.md)).
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
