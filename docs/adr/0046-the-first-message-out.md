# 0046 — The first message out

**Status:** Accepted · **Date:** 2026-10-08 · **Phase:** post-roadmap · **Follows from [ADR 0045](0045-told-means-yours.md)**

## Context

ADRs [0044](0044-a-queue-for-what-is-gone.md) and [0045](0045-told-means-yours.md) built a queue for
sold-out SKUs and held a unit for each shopper told. Both published `BackInStock`, and both said nothing
consumed it. A shopper found out by asking `GET /waitlist/mine` — which nobody does unprompted — so the
ten-minute hold mostly lapsed unseen, and the unit walked down a queue of people who never knew.

The platform had never sent anything to anybody. No email, no push, no SMTP configuration anywhere. This
is the first outbound message.

## Decision

**The user service sends it: it hears `BackInStock` over Kafka, records a notice, and a scheduled sender
emails it, retrying for as long as the mail server takes.**

### Why the user service, and not a notification service

A separate notifier is the conventional shape, and it would need the shopper's email address, which lives
only in the user service. It would either call the user service over HTTP for every notice — a synchronous
dependency and a service-to-service credential neither has — or the user service would first have to
publish user events through an outbox so the notifier could keep a copy. Both are more machinery than the
feature, built before the first email.

The user service already owns who a person is and how to reach them. Inventory still decides who is told
and when; this service decides only how. If notifications grow beyond one kind, the sending code moves
out with this reasoning on record.

### Received and sent are separate steps

The Kafka handler inserts a `PENDING` row and returns; it never waits on a mail server. A scheduled sender
claims due rows with `FOR UPDATE SKIP LOCKED` and sends them one per transaction.

That split is the whole answer to "what happens when the mail server is down". A handler that sent
directly would spend the consumer's few seconds of retries on the outage and then dead-letter the notice.
Here the notice waits: it is retried at thirty seconds, then a minute, doubling to a ceiling of thirty
minutes, with **no attempt limit**, because nothing improves by giving up on telling somebody their unit
is held.

### Once per place in the queue

The notice is keyed by the **waitlist entry**, not the event. The processed-events claim already drops a
redelivered message, but it dedupes by event id; the same decision published twice carries a new one. The
entry's primary key turns the second into a no-op, so it never becomes a second email.

**Exactly once is not available.** If the mail server accepts a message and the commit that records it
then fails, the notice is sent again. No mail protocol takes part in a database transaction. This is
at-least-once with that one window, stated rather than implied.

### What is not sent

- **A notice whose hold has lapsed.** If mail was down past `heldUntil`, the unit has already passed to the
  next shopper. "We are holding one for you" would be false, and "it is back" would send them after
  somebody else's unit. Recorded as `SKIPPED` / `HOLD_LAPSED`.
- **A notice for a customer this service does not know.** `SKIPPED` / `NO_RECIPIENT`, rather than retried
  for ever.

A notice with no hold (inventory could not make one) is still sent, without promising one.

### Watching it

`flashcart_user_notices_oldest_pending_seconds` is how long the oldest unsent notice has waited.
`NoticesBacklogged` fires when it passes **ten minutes — the hold window**: beyond that, notices are being
skipped as stale rather than sent, so a mail outage is quietly costing people their place. It routes to
`ticket` (ADR 0043), and has rule tests like every other rule.

### What stopping the mail server actually showed

The design above passed its tests. Stopping Mailpit under the running stack found three things it had not
said:

- **No timeout.** JavaMail waits for ever by default. The first attempt began while Mailpit was going
  down, its address still resolved, and the connect simply hung — the notice was never even recorded as
  attempted until Mailpit came back. That hang held the one scheduler thread every `@Scheduled` job in the
  service shares. Connect, read and write now time out at five seconds, and a run of sends **stops at the
  first refusal**, because every notice behind it would spend its own timeout learning the same thing.
- **The mail server's health was the service's health.** Spring Boot's mail health indicator connects to
  SMTP on every health check, so while Mailpit was down the user service reported itself **DOWN** — to
  compose, and to anything that restarts unhealthy containers. Sign-in would have gone down because email
  had. The indicator is off; the backlog gauge and its alert say the true thing instead.
- **Two clocks.** Rows were written with the database's `now()` and claimed against this JVM's. When the
  Postgres container's clock ran ahead, a notice made due "now" was not yet due, and a test passed alone
  and failed in the module run. The queue now uses the database's clock throughout.

Run again with those fixed: the notice failed, was recorded (`PENDING`, attempt 1, the connection error),
the service stayed `UP`, and it was sent on attempt 2, 23 seconds after Mailpit returned.

### Mailpit

Locally, the mail server is Mailpit: SMTP that delivers nothing and shows everything, at
<http://localhost:19025>. A real relay is a host, a port and credentials.

## Also found

### The demo seeds made every new migration crash a running stack

The user service's demo accounts are migrations numbered V900 and V901, so they run after the schema on a
fresh database. On a database that already has them, V4 sorts below an applied seed, and Flyway refuses to
start with *"Detected resolved migration not applied to database"*. The user service crash-looped on the
first `docker compose up`. CI never sees this, because CI's database is always fresh.

Catalog uses the same convention (V900) and would have done the same on its next migration. Both services'
`demo` profiles now set `out-of-order: true` — the one place there are seeds to be out of order with.

### The stack's health check passed a crash-looping service

While the user service was crash-looping, the local health loop — copied from CI's — reported every service
healthy. A restarting container has state `restarting` and an **empty** health, and the filter read empty
health as "has no healthcheck" and passed it. CI's "Start the stack and wait for health" step had the same
filter, so it could never have caught a service that dies on startup. It now requires every container to
be `running`, and healthy where it has a healthcheck.

### The user service's integration tests had never run in CI

CI's integration matrix listed catalog, inventory, order, payment and shipping. The unit job runs with
`-DskipITs`. So `UserIT` and `DevelopmentOperatorSeedIT` — authentication, sign-in, the seeded operator
check — had run on a laptop and nowhere else since the service was added. Every other module with
integration tests was in the list. `user` is now.

## Verified

- **10 integration tests** in `BackInStockNoticeIT`: one email with the hold time; one email per place in
  the queue across two publications; a mail outage delays and does not lose; one refusal ends the batch; a
  mail outage leaves the service `UP`; a lapsed hold is not sent; an unknown customer is skipped; no hold
  still sends without promising one; the backlog gauge rises and returns to zero; the backoff curve. The
  existing user tests pass with Kafka now on the classpath.
- **Seven mutations**, each caught: keyed per event instead of per place; no backoff; stale holds still
  sent; a failure marked as sent; the gauge reading nothing; the mail health indicator back on; the batch
  continuing past a refusal.
- **Live, on the running stack**: a shopper queued, stock was received, and exactly one email arrived in
  Mailpit; a stranger's order was cancelled and the shopper's went through. Then the outage run above.
- **Rule tests** for `NoticesBacklogged`. Its healthy case **survived a mutation on the first round**: a
  noisy series dipped to zero often enough that a one-second threshold slipped through between
  evaluations. It is now a sender steadily a few seconds behind.
- **The CI waitlist step** now also reads Mailpit's API and requires exactly one email to the shopper
  about the SKU.

## Alternatives considered

**A notification service.** See above: twice the size, and the extra half is plumbing to reach data this
service already has.

**Send in the Kafka handler.** Simplest, and it turns a mail outage of more than a few seconds into
dead-lettered notices.

**A Mailpit Testcontainer in the integration tests.** Real SMTP in tests, but it cannot be made to refuse
on demand, and refusal is the case this design is for. The tests use a recording sender that can be told
to fail; the real SMTP path is exercised by the compose stack and CI.

## Consequences

**Good.** Being told now means an email arrives, saying until when the unit is held. A mail outage costs
time, not notices, until it lasts longer than the hold — and then an alert says so.

**The user service now runs Kafka and an outbox**, with the same two tables as every other service, so the
shared code meets the schema it expects. It publishes nothing yet.

**Still open.**

- Other events a shopper would want emailed — an order confirmed, shipped, refunded — now have somewhere
  to go and do not go there yet.
- Production accounts still get `OPERATOR`; the load harness still signs in as the operator. Next.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
