# 0026 — Deleting an audit record is a decision

**Status:** Accepted · **Date:** 2026-09-14 · **Phase:** post-roadmap

## Context

[ADR 0025](0025-record-what-an-operator-reads.md) created `operator_access_log` and named this in the
same breath:

> Nothing expires these rows. The outbox has a retention sweeper; this table has none, so it grows
> with operator activity forever. That is the correct default for an audit trail and the wrong one
> for a disk, and a retention policy is a decision about how long the platform wants to be able to
> answer the question.

So the gap was created and documented on the same day. This record closes it, and the interesting
part is not the sweeper — [`OutboxRetention`](0017-outbox-and-processed-events.md) already showed how
to write one — but what its default should be.

## Decision

**A sweeper, and it deletes nothing until somebody says how long to keep.**

`flashcart.audit.retention` is unset by default. When unset, no rows are deleted and no query is even
run. When set, a scheduled sweep removes records older than the window, in batches, and logs how many
and how old.

### Why this is not the outbox's retention, despite looking identical

`OutboxRetention` prunes rows that have **done their job**. A published outbox message was
acknowledged by the broker and nothing will read it again; deleting it loses history and nothing
else. A default window is an easy call there, and ADR 0017 picked seven days without much agony.

An audit row has no such moment. It is the only record that an access happened, it is never read on a
normal day, and it is most valuable long after it was written. Deleting one is not cleanup — it is
the destruction of the evidence the table exists to hold.

**And the two failure modes are not symmetric.** A full disk is visible, recoverable and
embarrassing. An access nobody can reconstruct is indistinguishable from an access that never
happened, and nothing announces that it has become unanswerable. Defaults should fail toward the
recoverable side.

### Off by default is the decision ADR 0025 asked for, not a dodge

ADR 0025 said how long the platform should be able to answer "who read my data" is a real decision.
A library default quietly deleting a year of audit history because nobody set a property would be
exactly that decision, made silently, by whoever wrote the default, for every deployment that
inherits it.

So the *mechanism* ships and the *policy* does not. The compose stack sets `P365D` explicitly, which
is both a working demonstration and a worked example of what an actual choice looks like.

### The other failure is not silent either

When no retention is configured the service says so once at startup, with the current row count.
Unbounded growth is then something somebody chose to leave rather than something nobody noticed —
which is the whole difference between debt and a surprise.

Once at startup, not on every sweep: a warning on a six-hourly timer is one people stop reading, and
this project has already established that a check nobody reads is a check that does not exist.

**A sweep that deletes anything logs it.** Deleting audit records is itself an event somebody may
need to find afterwards, and a retention job is the one thing in the system whose job is to make
evidence disappear.

## Alternatives considered

**A sensible default — ninety days, a year — with the property to override it.** The conventional
choice and what most libraries do. Rejected because it inverts which mistake is recoverable: every
deployment that never reads this ADR silently starts destroying audit history on a schedule I chose,
and the first time anyone notices is when they go looking for a record that a sweeper deleted.

**Never delete; document the growth and leave it.** Honest, and what ADR 0025 shipped. Rejected
because "we know it grows without bound" stops being a considered position the second time it is
written down — and the operator of a real deployment has no mechanism at all, only a `DELETE` to
write by hand under pressure.

**Archive rather than delete: copy to cold storage first.** The right answer for a system that must
retain for years, and disproportionate here — it needs somewhere to put them, a format, and a way to
read them back, all to solve a problem this platform does not yet have. The sweeper does not preclude
it; a deployment needing archival sets no retention and does its own thing.

**Delete by row count rather than age.** Bounds the disk, which is the stated problem, and answers
the wrong question: "keep the last million accesses" is not a retention policy anybody can reason
about, and it deletes fastest exactly when activity is highest — which is when the records matter
most.

## Consequences

**Good.** The mechanism exists, its default cannot lose evidence, and the running stack documents a
real choice rather than an accident.

**The disk is still unbounded by default, and that is deliberate.** This closes the gap by making the
decision available and visible, not by making it for everybody. A deployment that sets nothing gets a
startup line telling it exactly that.

**Tested in both directions, which is the point.** That a configured window deletes old rows is the
easy half. That an unconfigured one deletes nothing *however old the rows are* — including a record
from eleven years ago — is the half that would let a bad default through unnoticed. Zero and negative
windows are tested too, because `PT0S` reaching this code should mean "keep", not "delete
everything", and that distinction is one typo wide.

**Still open.** Per-service ports remain published, so each service keeps checking for itself rather
than trusting the gateway; ADR 0022's reasoning is unchanged. An operator still cannot read another
customer's *order*, which ADR 0025 noted is not obviously right. And the audit table has no reader —
answering "who read my data" means a SQL query, not an endpoint, which is honest for now and would
not be if anybody outside the team needed the answer.
