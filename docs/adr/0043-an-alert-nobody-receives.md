# 0043 — An alert nobody receives

**Status:** Accepted · **Date:** 2026-10-05 · **Phase:** post-roadmap · **Closes what [ADR 0042](0042-every-alert-went-quiet-together.md) left open**

## Context

[ADR 0041](0041-the-alert-that-forgot.md) made the refund alert stay on until the money was settled.
[ADR 0042](0042-every-alert-went-quiet-together.md) made a service going down an alert of its own, and
put every rule under test. Both closed by naming the same two gaps:

- **No Alertmanager.** Prometheus evaluated nine correct, tested rules and showed the results on its own
  `/alerts` page. Nothing was sent anywhere. A critical page about money owed to a customer reached
  exactly as many people as had that tab open.
- **Nothing watches the watcher.** Every rule, `ServiceDown` included, can only report a fault while
  Prometheus is alive to evaluate it. A dead Prometheus is the one failure that produces no alert,
  because it is the thing that produces alerts.

After two records spent making alerts tell the truth, the alerts still told nobody.

## Decision

**Route alerts to a receiver, and make the absence of a heartbeat the alarm.**

### Alertmanager, with three receivers

`infra/alertmanager/alertmanager.yml` routes by severity:

| receiver | gets | re-sent |
|---|---|---|
| `page` | `severity="critical"` | hourly while it lasts |
| `ticket` | everything else, **including alerts with no severity at all** | every 4h |
| `heartbeat` | `Watchdog` only | every minute |

The default is the quiet one: an alert has to declare itself critical to page anybody, and a rule that
forgets its severity label still reaches a human queue rather than nowhere.

`ServiceDown` inhibits the other alerts of the same service. When a service stops being scraped its
alerts resolve within a scrape or two anyway (ADR 0042); this covers that minute, so the page says
"payment is down" once instead of that plus a stale symptom of it.

### A local sink, standing in for a pager

Every receiver is `alert-sink`, a short standard-library Python server in `infra/alert-sink/`. It logs
one line per alert, so `docker compose logs alert-sink` reads as a pager history, and lists what it has
received at `GET /alerts`.

That is what makes this decision testable on a laptop. In a deployment the three URLs change and
nothing else does: the routing, grouping, repeat intervals and inhibition are the real decision, and
they are exercised by the stack exactly as configured.

### The dead man's switch

`Watchdog` (`vector(1)`) fires permanently. Alertmanager forwards it to the sink every minute, and the
sink's `/health` returns **503 when it has not arrived for three minutes**, so `docker compose ps` shows
the sink unhealthy. A dead Prometheus, a rules file that will not load, or a stopped Alertmanager all
look the same from there: the heartbeat stops.

It depends on no metric, deliberately. A Watchdog built on, say, `up` would go quiet exactly when the
scrape target did, which is the failure it exists to expose. A test asserts it fires with no input
series at all.

## What the live stack showed

| action | result |
|---|---|
| stack started | first heartbeat within a minute |
| `docker compose stop payment` | `[page] firing ServiceDown service=payment` at the sink after 86s |
| `docker compose start payment` | `[page] resolved ServiceDown` after 60s |
| `docker compose stop prometheus` | sink `WATCHDOG_SILENT`, HTTP 503, container unhealthy after **350s** |
| `docker compose start prometheus` | healthy again after 23s |

**The switch took about six minutes, not three**, and the sink's own answer says why: when it tripped,
the last heartbeat was 227 seconds old. Alertmanager keeps re-sending an alert it already holds until
that alert's `endsAt` passes, and Prometheus sets `endsAt` minutes ahead. So for a couple of minutes
after Prometheus died, Alertmanager went on delivering a heartbeat from a server that no longer existed.
Detection time is that expiry plus the threshold. It is recorded as measured, not as configured.

### The first version of the switch was always tripped

The sink's healthcheck asked `http://localhost:8080/health`. In Alpine, `localhost` resolves to `::1`
first, the sink listens on IPv4, and every probe was refused: the container read **unhealthy from the
moment it started**, while `/health` answered `ok` from outside. A dead man's switch that is always
tripped is one people learn to ignore, which is a worse state than not having one. It now probes
`127.0.0.1`.

## Verified

- `infra/prometheus/test-rules.sh` now also runs `amtool check-config` and five routing cases, each
  asserting the receiver it must reach (`--verify.receivers`). Four mutations, each caught: critical
  routed to `ticket`, the heartbeat route removed, the default made `page`, and `Watchdog` rebuilt on
  a metric.
- A CI step, **Alerts reach a receiver**, asserts a heartbeat arrives at the sink and that Prometheus
  is sending to Alertmanager. It was run against the live stack, and run again with Alertmanager
  stopped, where it fails.

## Alternatives considered

**A third-party webhook echo image.** Several exist. A supply chain added to receive a JSON post, in
the one container whose job is to be trusted when everything else is failing, was not a good trade
against a hundred-odd lines of standard library.

**An external dead man's switch** (a hosted heartbeat service). The right answer for a deployment, and
the URL to swap in. Not here: a laptop stack that phones out to a third party on a timer is a surprise.

**MailHog and an email receiver.** Realistic, and one more image for no extra information: the routing
decision is the same whatever the transport.

## Consequences

**Good.** A firing alert now arrives somewhere a person can see it, split by whether it should wake
them. Silence from the whole pipeline is itself visible.

**Two more containers**, both small. `alert-sink` holds what it receives in memory only; a restart
empties the inbox, which is correct for a local stack and would not be for a deployment.

**Still open.**

- Nothing pages a human for real: the receivers are a local sink by design.
- If the sink itself is down, the switch cannot report it. `docker compose ps` still shows it, but
  only to someone who looks.
- Production accounts still get `OPERATOR`; the load harness still signs in as the operator.
- Whether a customer may read their own access log ([ADR 0034](0034-who-read-my-data.md)).
- The publisher still stores a hard-coded sampled flag; catalog has no `_info` endpoint.
