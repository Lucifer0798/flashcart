# 0024 — The development operator is not schema

**Status:** Accepted · **Date:** 2026-09-12 · **Phase:** post-roadmap

## Context

[ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md) created an operator account in
`V2__roles_and_operator.sql` and said, in the migration itself and in the README, that any deployment
keeping that row has no operator security at all. [ADR 0023](0023-a-customer-may-see-their-own.md)
sharpened it: that account can now read every customer's payment history.

Saying so was not nothing — a seeded credential nobody remembers is worse than one everybody knows
about. But a comment is not a control, and this one had been the only thing standing between a
published BCrypt hash and any database the migration ran against.

**The project had already solved this.** The catalog service puts its demo rows in
`classpath:db/seed`, a location only the `demo` profile adds to the Flyway path, with a comment
saying demo rows "can never reach an environment that did not ask for them by name". A credential is
a far better candidate for that treatment than a list of headphones. The operator seed simply did
not use the convention that was sitting next to it.

## Decision

**Schema migrations create schema. Seeds create rows. A credential is a row.**

The `roles` column stays in `V2` where it belongs. The account moves to
`db/seed/V900__development_operator.sql`, applied only under `demo` — the same location, version
range and `ON CONFLICT DO NOTHING` convention the catalog uses. Compose sets the profile, so
`docker compose up` still produces a stack whose harnesses can sign in; anything that does not ask
for the profile by name has no operator account at all.

**V2 is not edited.** It has already run against real databases, and rewriting an applied migration
replaces a security problem with a checksum failure on every existing volume — trading a risk
somebody might take for a breakage everybody definitely gets.
`V3__development_operator_is_not_schema.sql` deletes what V2 created instead. Under `demo`, V900 then
puts it back; without, it stays gone. The row never serves a request in between, because migrations
finish before the service accepts traffic.

**Not an environment variable at boot.** The V2 comment argued against reading the password from the
environment on the grounds that a service rewriting its own credentials every boot is harder to
reason about than a row somebody can `UPDATE`. That reasoning still holds and this change does not
overturn it — it changes *where the row comes from*, not what it is. Rotating the account is still a
single `UPDATE`.

**And a check, because the control only covers the path it controls.** The profile governs how the
account is *created*. It says nothing about a database copied from a development machine, a seed run
by hand, or a profile removed after the fact. `SeededOperatorCheck` logs an ERROR at startup naming
the account if it finds that row while `demo` is off.

It logs rather than refusing to start. Refusing is the louder signal and the wrong trade: this is a
condition in data, not in configuration, and turning drifting data into a failed startup converts a
security note into an outage — possibly during a deploy that changed nothing relevant.

## Alternatives considered

**Delete the account entirely and have each harness register its own operator.** The cleanest answer
to "no credential in the repository", and rejected because there is no endpoint that grants a role,
deliberately — ADR 0022 made becoming an operator an `UPDATE` somebody runs on purpose. Adding one so
that harnesses can self-promote would put a role-granting endpoint on the platform to avoid a row in
a seed file, which is a much worse trade than it first looks.

**Generate a random password at first boot and log it.** Removes the published hash, and replaces it
with a credential that changes every time the stack restarts — so every harness would have to scrape
it out of container logs. That is a worse harness, and a log line containing a live password is not
obviously an improvement on a file that says it is a development credential.

**Leave it, and rely on the comment.** It is what the previous two ADRs did, and the reason this one
exists. The comment was honest and had no effect on anything.

## Consequences

**Good.** The dangerous state now requires somebody to ask for it by name, and the safe state is what
you get by doing nothing. That is the right way round, and it is the same shape as the default-deny
argument in ADR 0022: the failure mode of forgetting should be *closed*.

**The tests are two-sided on purpose.** `DevelopmentOperatorSeedIT` proves the seed works under the
profile; `UserIT` proves the account is absent without it. Only the second one tests the property
that matters. A suite with just the first would pass just as happily if the seed ran everywhere —
which is exactly the bug being fixed.

It also pins the published password to the stored hash: the hash was generated by hand, and a
documented password that has quietly stopped matching is a support call that starts with "it works on
my machine".

**A cost worth naming.** Running the user service outside compose now yields no operator, so anything
driving the platform by hand needs `SPRING_PROFILES_ACTIVE=demo`. `scripts/operator-token.sh` says so
on a 401 rather than leaving somebody to work backwards from a refusal through three layers of
harness.

**V3 deletes that row from every database it runs against, and that deserves saying out loud.**
Anyone who kept the seeded account deliberately and rotated its password to a real one loses it on
upgrade — the delete matches on the fixed id, not on whether the password is still the published one.
That is the correct outcome by this ADR's own argument, since an account created by a schema
migration is exactly what it is removing, but "correct" and "expected" are different things and this
is the sort of change that should not be discovered. The fix for such a deployment is to create an
operator the way ADR 0022 describes: an `INSERT` with a fresh id and a hash of your own, which is
then nobody's seed and nothing will delete it.

**Still open, and now the largest remaining item.** Per-service ports are still published, so the
gateway remains an optimisation rather than a boundary — ADR 0022's reasoning is unchanged. And an
operator's access to customer data is still unaudited: there is no record of which operator read
whose payments.
