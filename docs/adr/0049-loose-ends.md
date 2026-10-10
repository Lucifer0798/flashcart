# 0049 — Loose ends

**Status:** Accepted · **Date:** 2026-10-10 · **Phase:** post-roadmap · **Closes items left open by [ADR 0020](0020-a-trace-must-survive-the-outbox.md), [ADR 0027](0027-the-outbox-hop-is-a-span.md) and [ADR 0036](0036-anyone-could-set-the-price.md)**

## Context

Three items had been carried forward in the *still open* list of nearly every record since about ADR
0026: a hard-coded tracing flag, catalog's missing `_info`, and a README claim about host ports. Each was
small. Closing them turned up as much as each was worth on its own.

## Decision

### The outbox stores the trace's real sampled flag

[ADR 0020](0020-a-trace-must-survive-the-outbox.md) stored the `traceparent` with its sampled flag
hard-coded to `01` — honest at a sampling rate of 1.0, and every service reads `TRACE_SAMPLING` from its
environment. Below 1.0, the relay would resume every dropped trace as sampled, and under a parent-based
sampler record it. [ADR 0027](0027-the-outbox-hop-is-a-span.md) made the outgoing header carry the hop's
real flags but left the *stored* value alone, reasoning that reading a live `SpanContext` would give
`flashcart-common` a hard dependency on OpenTelemetry.

It does not have to. The publisher now reads `Span.current()`'s context — trace, span and the real
flags — through a nested holder class that is only loaded when OpenTelemetry is on the classpath, checked
once with `ClassUtils.isPresent`. The dependency stays optional; a service without tracing stores no
context, exactly as before.

Verified on the running stack, where it counts: with `TRACE_SAMPLING=0` on the gateway and order
service, all five messages one order queued stored a context ending **`-00`**, and at the default every
one ended `-01`. The first attempt at that check proved nothing: `compose.yaml` does not pass
`TRACE_SAMPLING` through, so setting it in the shell changed nothing and the "unsampled" orders stored
`01`. It was rerun with an override that sets it inside the containers. CI's "one trace across the
outbox" step still passes, which is the other half — a stored context that went missing would break it.

### Every service answers `_info`, and says what is true

Catalog had none, though the gateway has routed `/api/v1/catalog/**` to it since the first commit. It
now has one, public like the others.

Looking for where `_info` was checked found worse: **the user service reported `"status":"skeleton"`,
"implemented in Phase 4", and CI asserted that word.** The one check of the user service's `_info`
insisted it was unfinished, through registration, sign-in, roles and email. Its own test was named "the
service still reports itself live" and checked only the HTTP status. It now reports `live`, the test
checks the body, and CI requires `live` from all six services in one loop.

### Every host port is the usual one with a 1 in front

The README said every host port sat in 15000–19000. They run from 13000 to 19411; the real rule, which
every port followed but one, is the conventional port with a 1 prefixed — 15432, 16379, 18080, 19090,
13000. The exception was Mailpit's web inbox, which ADR 0046 put on 19025 rather than 18025. It moves, and
the README states the rule that is actually followed.

### Both lists of decision records are checked

`docs/README.md` maps the records by subject. It had stopped at 0039 through nine more records, while its
first line went on saying "twenty decision records". Its last group had also become a catch-all, holding
tracing and the order lifecycle alongside access control; it is regrouped, with two new groups for the
order's life and for telling people.

A hand-written list a reader trusts and nothing checks will drift again. `scripts/adr-index-check.py`
runs in CI's unit job and fails if any record is missing from either list or a link from either does not
resolve. It was made to fail both ways before it was trusted. Neither page states a count any more.

## Verified

- `OutboxTraceParentTest`: the publisher stores `-00` for an unsampled span and `-01` for a sampled one,
  and nothing when there is no trace. Putting the hard-coded `01` back fails it.
- `CatalogIT` and `UserIT` check `_info` reports `live`; removing catalog's public rule, or putting
  "skeleton" back, fails each.
- Live: all six `_info` endpoints `live` through the gateway; stored trace flags as above; CI's trace and
  paid-cancellation steps pass, the latter reading Mailpit on its new port.

## Consequences

**Good.** The three oldest items on the list are gone, and two of them turned out to be hiding a check
that asserted something false.

**Still open.**

- `compose.yaml` does not pass `TRACE_SAMPLING` to the services, so the stack cannot be run at another rate
  without an override.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- A refund recorded as made outside the platform sends no email, nor does a shopper's own cancellation of
  an unpaid order ([ADR 0048](0048-the-order-tells-its-own-story.md)).
