# 0072 — An address earns its typos

**Status:** Accepted · **Date:** 2026-11-13 · **Phase:** post-roadmap · **Amends [ADR 0058](0058-a-guess-costs-more-than-a-hash.md), on the evidence of [ADR 0071](0071-everyone-signs-in-at-once.md)**

## Context

ADR 0058 limits failed sign-ins three ways. One of them is per client: 30 failures in 15 minutes from one address,
whatever accounts they were for. That catches credential stuffing, a leaked list tried against many accounts once
each, which the per-account limits never see. It is checked before the password, so while it holds, nobody at that
address gets in, right password or not.

ADR 0071's load run had 1,000 shoppers sign in at once from one address, as everyone behind an office or a mobile
carrier's NAT does. 5% mistyped once and then typed it right. The per-client limit closed after 36–40 typos, and
**314 and 395 of the 1,000 shoppers were then refused with the right password**, for up to fifteen minutes. Neither
ADR 0058 nor the reset limit's ADR 0060 had weighed many people sharing an address.

Counted by failures alone, the two cases are the same: many accounts, a failure each, one address. The difference
is everything else from that address. Honest shoppers mostly get in, while stuffing mostly does not.

## Decision

### Each established account that gets in buys the address one more failure

The per-client limit holds at 30 failures **plus** the number of distinct accounts that signed in successfully from
that address within the same 15 minutes. A sale's worth of shoppers behind one carrier address earns room for its
typos as fast as it makes them. A stuffer, whose guesses mostly fail, earns almost none.

The other two limits are unchanged. The tight one for one account from one place (5 failures) still stops somebody
guessing one person's password from a busy office, and the per-account limit still stops a guess spread across many
machines.

Successes are counted by account, not by sign-in, so one account signing in again and again earns one. They are kept
as hashes, the client's and the account-and-client pair's, in `sign_in_successes`, the same way `throttled_attempts`
keeps failures. They are pruned once older than the window. Changing a password also proves one, so it counts too.

### Two guards, because registration is free

Registration needs no email verification and has no limit. Credit alone would sell guesses: register an account,
sign in from the attacking address, guess once more, and repeat. So:

- **Only accounts at least a day old earn credit.** Accounts made for an attack earn nothing during it. Shoppers at a
  sale overwhelmingly have accounts already, and one who registers on the day just doesn't add to their address's
  credit; they lose nothing.
- **The credit is capped at 300 per address.** An attacker who prepared accounts a day ahead and signs into each from
  the attacking address gets at most 330 guesses per 15 minutes from it, not 30, and not unlimited. That is the
  defence this change gives up, deliberately and boundedly. 300 covers a shared address with about 6,000 shoppers at
  a 5% typo rate.

Both are settings: `flashcart.sign-in.limit.client.credit-cap` and
`flashcart.sign-in.limit.client.credit-minimum-account-age`.

### Counted only when it could matter

The credit is looked up only when the limit would already hold without it, and the count stops at the cap. The first
version counted on every sign-in. At a sale, that meant scanning one address's thousands of successes on every request,
on the requests already short of connections (ADR 0071).

### Written without waiting for the disk

Every successful sign-in writes a row now, and `sign_in_successes` is an **unlogged** table. A logged write makes its
commit wait for a disk flush, and it holds one of the pool's ten connections while it waits. Before this change, a
successful sign-in's commit usually wrote nothing, so it never waited. The rows are fifteen-minute hints. If the
database crashes, the table is emptied, and every address starts its next window with no credit.

The clearing of the pair's failures and the recording of the success are also one statement, not two. Each statement
here takes a connection from the pool.

## Measured

With `load/sign-in.sh` against the stack, before and after on the same day. Seeded accounts are a week old, as a
sale's shoppers' mostly are.

**The lockout is gone.** 1,000 shoppers from one address, 5% mistyping once first:

| | Signed in | Right password refused (429) |
|---|---|---|
| before (ADR 0071) | 605, 686 | 395, 314 |
| after, four runs | 1,000 every time | 0 every time |

Every typo was refused as a wrong password, and the books agreed every time.

**And it costs a sale nothing measurable, once it was unlogged.** This is not "nothing" by assumption. The first version
cost a lot, and only a direct comparison showed it. `main`'s image and this branch's were run alternately at 3,000
shoppers with no typos, each after a restart and a warm-up, three rounds each:

| | Shoppers shed (503), three runs |
|---|---|
| `main` | 275, 8, 20 |
| this change, success rows **logged** | 991, 739, 228 |
| `main`, again | 801, 35, 82 |
| this change, success rows **unlogged** | 52, 63, 98 |

Logged, every round was worse than the `main` run beside it. Unlogged, it sits inside `main`'s own spread. That
spread is wide, 8 to 801 for the same image on the same afternoon. That is why the comparison alternated, and why
a single run of either proves nothing.

That spread also corrects ADR 0071. Its two runs at 3,000 after capping the hashes shed nobody, and it said everyone
got in. `main` sheds 8–801 at 3,000 on this machine on other days. The cap made shedding rare. It did not make it
impossible.

Mutations: ten were run on the credit, and two more on the single statement that records a success. Each was caught by
the test written for it. They removed the credit, let new accounts earn it, removed the cap, counted sign-ins instead
of accounts, loosened every limit with it, took credit from any address, never aged it out, allowed one extra failure,
never pruned it, counted it and then discarded it, stopped a success clearing its pair, and skipped the age check.

## Consequences

- At a sale, typos from a shared address no longer lock out the honest shoppers behind it.
- A stuffer with accounts registered a day ahead can try up to eleven times as many guesses per address per window as
  before. The pair and account limits are unaffected, and the attacker still needs those accounts to sign in
  successfully from the same address each window.
- `Retry-After` assumes the credit stays as it is. If successes age out first the wait can be longer, and if more
  accounts sign in it can be shorter. It was always an estimate.

## What this leaves open

- **A locked address cannot earn its way out.** The check comes before the password, so once the limit holds, no
  success from there is recorded until failures age out. At a sale, successes accrue far faster than typos, so the
  load runs never reached the limit. A wave of typos arriving before almost any success could still close it.
- **Registration is still free.** The age rule and the cap bound what free accounts buy here. They do not make
  accounts cost anything, which would matter for any future rule that counts accounts.
- **Account age is a proxy.** "A day old" means "not made for this attack". It doesn't mean "trustworthy".
