# 0080 — A download is not a secret

**Status:** Accepted · **Date:** 2026-11-25 · **Phase:** post-roadmap · **Closes a gap named in [ADR 0066](0066-your-data-as-a-file.md)**

## Context

Five files hold everything the platform keeps about a customer ([ADR 0066](0066-your-data-as-a-file.md)):
their profile, addresses, kept emails, orders, payments and parcels. One access token gets all five, and it
takes no password, because a download changes nothing and can be repeated. ADR 0066 named the cost: a stolen
token's download "shows up in request logs and nowhere else". The owner had no way to know.

[ADR 0079](0079-a-stolen-token-cannot-sign-you-out.md) stopped a stolen token signing its owner out. This
makes its other easy harm visible.

## Decision

### Every file is announced, from the service that served it

Each `_export/mine` writes a `CustomerDataExported` event, with the customer id and its own service name, to
its own events topic through the outbox. The user service listens on all five topics and emails the account.

Announcing only the user service's file would have been one change instead of five. It would also leave the
files a thief wants most unseen: the shipping file has every delivery address, and the order and payment files
have the history. Nothing about the download asks the user service first, so each service has to say so itself.

`DataExport.of`, which every file goes through, now takes the publisher and the topic and announces the file
before answering. A service cannot hand over a file without the announcement, because it cannot build one
without passing them. The announcement goes through the outbox in the request. If it cannot be written, the
download fails rather than happening unseen. A file announced and then lost on the way out still gets its
email, which is the side to err on.

### One email an hour

A whole copy is five files, seconds apart, and five emails saying the same thing teach the owner to ignore
them. The email is keyed by customer and clock hour (`data-exported:<customer>:<hour since epoch>`), and the
notice queue's key already drops a second notice with the same key
([ADR 0046](0046-the-first-message-out.md)). The first file of the hour sets the time the email gives.
Downloads at 10:59 and 11:01 make two emails. That is accepted: it is still two, not ten, and a rolling window
would need state the queue does not keep.

### What it says

> **A copy of your FlashCart data was downloaded**
>
> Someone signed in to this account downloaded a copy of the data we hold about you at 14:02 UTC. It can
> include your name, email and addresses, your orders and payments, and the emails we sent you.
>
> If that was you, there is nothing to do.
>
> If it wasn't, someone is signed in as you. Change your password now, or reset it with "Forgot password",
> and every device will be signed out.

It is written for the owner who did it themselves, who is nearly everyone, and still tells the one who didn't
what was taken and what to do. It names no device or address: the platform does not record a device, and the
address the service sees is often the gateway's or a shared one.

Like every notice, it goes stale a day after the download, and a closed account is not emailed. Closing erased
the address, and the user service's own file already refuses a closed account.

## Considered and not chosen

- **A password prompt on the export**, which ADR 0066 offered as the small change if the emails proved
  sensitive. It would stop a stolen token instead of reporting it, but downloading your own data should not be
  harder than reading it one piece at a time, which the same token can already do. Left open, now that the
  owner would at least know.
- **Calling the user service from each export.** A synchronous call would make every download depend on the
  user service being up, which ADR 0066 avoided on purpose. The event leaves each file depending only on its
  own service.

## Consequences

**Good.** A download by somebody else is no longer invisible. The owner hears within seconds, once, with what
to do.

**Accepted.**

- **The owner downloading their own data gets an email too.** Unavoidable if the thief's is to be seen, and
  worded for it.
- **The listener's five topics are checked live, not by a test.** The tests drive the listener directly and
  check each service publishes on its own topic. That the listener subscribes to all five was shown on the
  compose stack.

**Still open.**

- A stolen access token can still download everything within its fifteen minutes; now the owner is told.
- Staff opening one reservation is not recorded ([ADR 0067](0067-the-warehouses-read-goes-on-the-record.md)).

## Verified

- New tests:
  - each service's export test: a download publishes one `CustomerDataExported` for the caller, on that
    service's own topic, naming that service;
  - `DataExportIT` (user): a download refused for a forged token announces nothing;
  - `NoticeIT`: five files within an hour, then one the next hour, make two emails to the owner, each giving
    the time of its hour's first file and saying what to do.
- Mutations, all 6 caught: a download not announced; announced on the wrong topic; one email per customer
  ever; one per file; no time in the email; the database's check left refusing the new kind.
- Live, through the gateway on the compose stack: all five files downloaded (200 each), and one email,
  "A copy of your FlashCart data was downloaded", reached Mailpit two seconds later. A sixth file in the same
  hour sent nothing more. Every announcement was published from its service's outbox, on its own topic, and
  the user service's notice consumer claimed all six, one or two from each of the five topics.
