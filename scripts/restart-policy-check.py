#!/usr/bin/env python3
"""Fails if a container the stack starts by default would stay down after Docker restarts. See ADR 0081.

    python3 scripts/restart-policy-check.py     # `python` on Windows

When the Docker engine stops (Docker Desktop quitting or updating, its VM shutting down, the machine sleeping), every
container exits, and on the way back the engine starts only those with a restart policy. Only the seven services had
one. Postgres, Kafka and the rest stayed down, and the services restarted into a crash loop against them, three days
running. So every service compose starts by default must say `restart: unless-stopped` or `always`, itself or through
the anchor it merges. A container behind a profile is started by hand, for a look, and is left alone.

Reads compose.yaml as text rather than through a YAML library, so it runs on a bare python3, as CI's other checks do.
Checked against known answers first, because a check that cannot fail is worth less than none.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESTARTS = ("unless-stopped", "always")


def blocks(text: str, indent: int) -> dict[str, str]:
    """The entries at one indent under the text's top, by name, each with the lines nested under it."""
    found, name, lines = {}, None, []
    head = re.compile(r"^" + " " * indent + r"([A-Za-z0-9_.-]+):\s*(?:&\S+)?\s*(?:#.*)?$")
    for line in text.splitlines():
        match = head.match(line)
        if match:
            if name:
                found[name] = "\n".join(lines)
            name, lines = match.group(1), [line]
        elif name is not None and (line.startswith(" " * (indent + 1)) or not line.strip()):
            lines.append(line)
        elif name is not None:
            found[name] = "\n".join(lines)
            name, lines = None, []
    if name:
        found[name] = "\n".join(lines)
    return found


def restart_of(block: str) -> str | None:
    match = re.search(r"^    restart:\s*[\"']?([a-z-]+)", block, re.M)
    return match.group(1) if match else None


def problems(compose: str) -> list[str]:
    top = blocks(compose, 0)
    if "services" not in top:
        return ["compose has no services"]
    anchors = {}
    for name, block in top.items():
        anchor = re.match(r"^\S+:\s*&(\S+)", block)
        if anchor:
            match = re.search(r"^  restart:\s*[\"']?([a-z-]+)", block, re.M)
            anchors[anchor.group(1)] = match.group(1) if match else None
    found = []
    for name, block in blocks(top["services"], 2).items():
        if re.search(r"^    profiles:", block, re.M):
            continue
        restart = restart_of(block)
        if restart is None:
            merged = re.search(r"^    <<:\s*\*(\S+)", block, re.M)
            restart = anchors.get(merged.group(1)) if merged else None
        if restart not in RESTARTS:
            found.append(f"{name}: restart is {restart or 'not set'}, so it stays down after Docker restarts")
    return found


def self_check():
    base = "x-base: &base\n  restart: unless-stopped\n  mem_limit: 1m\n\nservices:\n"
    assert problems(base + "  a:\n    image: x\n    restart: unless-stopped\n") == []
    assert problems(base + "  a:\n    image: x\n    restart: always\n") == []
    assert problems(base + "  a:\n    <<: *base\n    image: x\n") == []
    assert problems(base + "  a:\n    image: x\n    profiles: [tools]\n") == []
    assert problems(base + "  a:\n    image: x\n") == ["a: restart is not set, so it stays down after Docker restarts"]
    assert problems(base + "  a:\n    image: x\n    restart: on-failure\n")
    assert problems(base + "  a:\n    image: x\n    restart: \"no\"\n")
    # A service after one with a policy, and a policy nested deeper than the service's own keys, are not credited.
    assert problems(base + "  a:\n    restart: always\n  b:\n    image: x\n    deploy:\n      restart: always\n") \
        == ["b: restart is not set, so it stays down after Docker restarts"]
    assert problems(base + "  a:\n    image: x\n\nvolumes:\n  data:\n") \
        == ["a: restart is not set, so it stays down after Docker restarts"]
    assert problems("x-other: &other\n  mem_limit: 1m\nservices:\n  a:\n    <<: *other\n")
    assert problems("volumes:\n  data:\n") == ["compose has no services"]


def main():
    self_check()
    compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
    found = problems(compose)
    checked = [name for name, block in blocks(blocks(compose, 0)["services"], 2).items()
               if not re.search(r"^    profiles:", block, re.M)]
    for problem in found:
        print(f"FAIL  {problem}")
    print(f"{len(checked)} default containers checked, {len(found)} would stay down after Docker restarts")
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
