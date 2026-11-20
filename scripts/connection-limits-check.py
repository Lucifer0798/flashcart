#!/usr/bin/env python3
"""Fails if a service could not take every connection the gateway may open to it at once. See ADR 0076.

    python3 scripts/connection-limits-check.py     # `python` on Windows

The gateway holds up to max-connections to each service (ADR 0074). After a quiet spell its pool is empty, because
idle connections are retired at 30 seconds, so a burst opens them all together. A service's Tomcat holds new
connections in its accept queue until its acceptor takes them. That queue was 100 by default, and 500 arriving at
once overflowed it: the kernel dropped the overflow, and 44 orders were reset at a sale's opening. So every service's
server.tomcat.accept-count must be at least the gateway's max-connections. The two numbers live in different files,
and nothing else would notice one changing without the other.

Checked against known answers first, because a check that cannot fail is worth less than none.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY_LIMIT = re.compile(r"^\s+max-connections:\s*(\d+)\s*$", re.M)
ACCEPT_COUNT = re.compile(r"^\s+accept-count:\s*(?:\$\{[A-Z_]+:)?(\d+)\}?\s*$", re.M)


def problems(gateway_yml: str, services: dict[str, str]) -> list[str]:
    limit = GATEWAY_LIMIT.search(gateway_yml)
    if not limit:
        return ["the gateway's pool has no max-connections"]
    needed = int(limit.group(1))
    found = []
    for name, yml in sorted(services.items()):
        accept = ACCEPT_COUNT.search(yml)
        if not accept:
            found.append(f"{name}: no server.tomcat.accept-count (Tomcat's default is 100, the gateway may open {needed})")
        elif int(accept.group(1)) < needed:
            found.append(f"{name}: accept-count {accept.group(1)} is less than the gateway's {needed}")
    return found


def self_check():
    gateway = "pool:\n  type: FIXED\n  max-connections: 500\n"
    assert problems(gateway, {"a": "server:\n  tomcat:\n    accept-count: 1000\n"}) == []
    assert problems(gateway, {"a": "server:\n  tomcat:\n    accept-count: ${ACCEPT_COUNT:1000}\n"}) == []
    assert problems(gateway, {"a": "server:\n  port: 8080\n"})
    assert problems(gateway, {"a": "server:\n  tomcat:\n    accept-count: 100\n"})
    assert problems("pool:\n  type: FIXED\n", {"a": "server:\n  tomcat:\n    accept-count: 1000\n"})


def main():
    self_check()
    gateway = (ROOT / "services/gateway/src/main/resources/application.yml").read_text(encoding="utf-8")
    services = {path.parents[3].name: path.read_text(encoding="utf-8")
                for path in sorted(ROOT.glob("services/*/src/main/resources/application.yml"))
                if path.parents[3].name not in ("gateway", "common")}
    found = problems(gateway, services)
    for problem in found:
        print(f"FAIL  {problem}")
    print(f"{len(services)} services checked against the gateway's pool, {len(found)} short")
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
