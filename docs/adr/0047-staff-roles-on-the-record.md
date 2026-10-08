# 0047 — Staff roles, on the record

**Status:** Accepted · **Date:** 2026-10-08 · **Phase:** post-roadmap · **Corrects [ADR 0039](0039-nothing-ever-ran-without-operator.md)**

## Context

[ADR 0038](0038-one-role-did-too-much.md) split `OPERATOR` into `WAREHOUSE`, `CATALOG` and `SUPPORT`, and
[ADR 0039](0039-nothing-ever-ran-without-operator.md) moved CI and the chaos harness onto them. Every ADR
since has carried two items forward: *production accounts still get `OPERATOR`*, and *the load harness
still signs in as the operator*.

Looking at what those meant, the first turned out to be less a policy than an absence. There was **no
way to make anybody staff** except an `UPDATE users SET roles = …` typed by hand — which is the right
friction, chosen on purpose in [ADR 0022](0022-being-signed-in-is-not-being-a-warehouse.md), but nothing
around it:

- **Nothing checked what was written.** `'WAREHOSUE'` was stored, put in every token the account was
  issued, and matched no rule anywhere. The account simply got `403`s, and nothing pointed at why.
- **Nothing made the narrow roles the easy path.** The only example of granting a role anywhere was the
  seed, and the seed grants `OPERATOR`.
- **Nothing recorded who granted what.** "Who gave this person `OPERATOR`, and why" had no answer.

And looking at the second found that ADR 0039 had got two things wrong:

- It said the load harness "only places orders and reads its own, so a shopper account would be the
  honest fit". It does neither. It seeds stock and reserves directly against inventory — warehouse work.
- It said one `OPERATOR` token was left in CI, the deliberate wrong-verb probe. There were two: CI's k6
  load step also passed `$OPERATOR` to the harness.

## Decision

**Make the narrow roles the path of least resistance, refuse role names that do not exist, and record
every change.**

### Only roles that exist

A check constraint on `users.roles` admits exactly a comma-separated list of `OPERATOR`, `WAREHOUSE`,
`CATALOG` and `SUPPORT`, or nothing. A typo is now an error at the moment it is written, not a silent
`403` days later. Existing rows already matched, so the constraint went on without a data change.

### `scripts/grant-role.sh`

Still no endpoint — ADR 0022's argument stands: anything that can grant a role over an API can be
tricked into granting one. The script is the hand-typed `UPDATE` made safe:

- **narrow by default.** `WAREHOUSE`, `CATALOG` and `SUPPORT` need only a reason. **`OPERATOR` is refused
  without `--break-glass`**, and the refusal says which narrow role covers which job
- **a reason, always.** A role change nobody can explain later is one nobody can review
- **on the record.** The change and a `role_grants` row — role, grant or revoke, reason, database user and
  OS user — are one transaction. Granting a role already held changes nothing and records nothing, so the
  trail is changes rather than attempts
- **injection-safe.** Every input reaches the SQL as a psql variable, quoted by psql, never spliced into
  the text. A reason of `it's fine'); drop table users; --` is stored as that sentence
- **distinct exit codes**: `2` bad arguments, `3` `OPERATOR` refused, `4` no such account. An unknown
  account first surfaced as psql's own exit code 3 — the same code as the refusal — and was split out

It runs against the compose stack's database by default and against any other with `DATABASE_URL`.

### The harness holds `WAREHOUSE`

`load/run.sh` and CI's k6 step now sign in as the seeded warehouse account. The run was repeated on the
narrow role: no errors, and exactly the seeded stock sold. The CI wrong-verb probe is now genuinely the
last `OPERATOR` token in CI.

## What this does not do

**It does not take `OPERATOR` away from anybody.** Nothing here revokes an existing grant, and the seeded
development operator keeps it — the harnesses' fallback probe depends on it, for the reason ADR 0039
gives. What changes is that granting `OPERATOR` now has to say it is breaking glass, and is recorded.

**It is not authentication.** Whoever can run the script already holds the database. `granted_by` is a
name to ask, not proof of who asked.

## Verified

- **`RoleNamesIT`**: every real role, alone and together, and none, accepted; `WAREHOSUE`, `warehouse`,
  `ADMIN`, a trailing comma, a leading one and a space after a comma all refused; a grant record without a
  reason or with an unknown role refused. Loosening the constraint to "any capitals and commas" turns the
  refusal test red.
- **The script, live, in ten cases**: grant, a repeat that records nothing, `OPERATOR` refused, `OPERATOR`
  with break-glass, revoke, unknown role, no reason, unknown account, the hostile reason, and a typo
  written straight into the database — refused by the constraint.
- **A CI step** makes a fresh account staff the way a deployment would: it cannot receive stock, cannot be
  given `OPERATOR` without break-glass, can receive stock once granted `WAREHOUSE`, still cannot create a
  product, and has exactly one `role_grants` row.

## Alternatives considered

**A grant endpoint for `OPERATOR`s.** Convenient, and exactly the surface ADR 0022 declined: one stolen
operator token would then mint more.

**A `roles` table and a join table.** The relational answer, and four roles in a constrained column is
the same guarantee for a fraction of the change. ADR 0022's reasons for the column still hold.

**Revoking `OPERATOR` from the seed.** The development operator is what the wrong-verb probe needs; the
seed is not a deployment.

## Consequences

**Good.** A misspelt role cannot be granted. The narrow roles are the default and `OPERATOR` has to be
asked for by name. Every grant from here on has a reason and a name beside it.

**Two corrections to ADR 0039**, annotated there.

**Still open.**

- Grants made before this have no `role_grants` row; the trail starts now.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
- Other events a shopper would want emailed — confirmed, shipped, refunded ([ADR 0046](0046-the-first-message-out.md)).
