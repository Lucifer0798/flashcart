# 0081 — The stack comes back by itself

**Status:** Accepted · **Date:** 2026-11-27 · **Phase:** post-roadmap

## Context

Three days running, the local stack was found half down. Postgres, Kafka, Redis, Mailpit, Prometheus, Grafana,
Alertmanager and the alert sink had all exited with code 255 at the same moment, which is the Docker engine
stopping under them: Docker Desktop quitting or updating, its VM shutting down, or the machine sleeping. The seven
services were up, but only for seconds at a time, restarting into a crash loop against a database and a broker
that were not there. It stayed that way until somebody ran `docker compose up -d` again.

When the engine starts, it brings back only containers with a restart policy. In `compose.yaml` only the seven
services had one, through the `x-service-base` anchor. None of the infrastructure did, so every engine restart
left the services running against nothing.

## Decision

**Every container the stack starts by default has `restart: unless-stopped`**: the nine infrastructure
containers now, as the seven services already did.

- **`unless-stopped`, not `always`.** A container stopped on purpose, with `docker compose stop` or
  `docker stop`, stays stopped across an engine restart. The load harnesses stop and restart single services,
  and a laptop owner may stop the stack to free memory. Neither should come back uninvited.
- **Kafka UI is left as it is.** It is behind the `tools` profile and started by hand for a look.
- **Nothing orders the start-up.** The engine starts every container at once and ignores `depends_on`. In
  the run below that did not matter: Postgres was ready within seconds, long before any service had booted, and
  no service needed a restart. A service that did start first would exit, and the policy would start it again,
  with back-off, until it could connect.

**`scripts/restart-policy-check.py` fails if a container the stack starts by default has no such policy**,
either its own or through the anchor it merges. CI runs it with the other repository checks. It reads
`compose.yaml` as text, so it needs nothing beyond a bare `python3`, and it checks itself against known answers
first. Run against `compose.yaml` before this change, it named exactly the nine containers that had been found
down, and credited the seven services.

The README said the stack was eleven containers including Kafka UI. It now lists the sixteen.

## Consequences

**Good.** Docker Desktop restarting no longer leaves the stack broken. It comes back in about a minute and a
half, and nobody has to notice first.

**Accepted.**

- **A container that keeps failing restarts forever,** with Docker's back-off between attempts, rather than
  staying down to be noticed. The services already did this. Their health checks and the alerts are how a
  failure is seen, not a stopped container.
- **Starting Docker starts the stack.** Anyone who leaves it running and later starts Docker for something
  else gets FlashCart too, until they stop it. `docker compose stop` is remembered.

**Still open.** Why the engine keeps restarting is outside this repository. This makes the stack survive it.

## Verified

- **The check fails first.** Against `compose.yaml` before the change, `scripts/restart-policy-check.py` listed
  the nine infrastructure containers and exited 1. After it: 16 checked, none failing. `docker compose config`
  resolves all sixteen to `unless-stopped` independently of the script. Its self-check covers:
  - a policy of its own, or one through an anchor;
  - `on-failure` and `"no"`, refused;
  - a policy nested under `deploy`, or on the previous service, not credited;
  - a profiled container, skipped.
- **Live, on the compose stack.** Docker Desktop was quit and restarted by hand while a script watched without
  touching anything. The engine was down for 18 s. With no `compose up` from anyone, all sixteen containers were
  back and healthy 87 s after it returned:

  | After the engine was back | Healthy |
  |---|---|
  | 3–4 s | Postgres, Redis, Mailpit, Grafana |
  | 12–13 s | Prometheus, Alertmanager, alert sink |
  | 52–65 s | Zipkin, gateway, Kafka, order, catalog, payment |
  | 76–87 s | user, shipping, inventory |

  Every container started once, at the same second, and none needed a restart. Then, end to end through the
  gateway, a customer downloaded all five data files (200 each). Every service published its announcement to
  Kafka from its outbox, the user service consumed all six, and the email reached Mailpit within a second.
- **Before the change.** The same event had left the infrastructure `Exited (255)` and the services
  crash-looping, on each of the three previous days.
