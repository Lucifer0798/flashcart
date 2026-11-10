#!/usr/bin/env python3
"""Fails if a service's tests could reach the compose stack running on the same machine. See ADR 0070.

    python3 scripts/test-isolation-check.py     # `python` on Windows

Each service's application.yml defaults to the stack's host ports, which is right for running a service by
hand next to the stack and wrong for its tests. A suite that kept those defaults joined the running services'
consumer groups, took their partitions, and published into their topics; nothing failed, because the tests
were green and the stack was only a little wrong.

So every module whose application.yml points at a stack port must have
src/test/resources/config/application.yml pointing the same thing at port 1, where nothing listens. A new
module, or one that gains Kafka later, fails here until it has one. The check runs against known answers
first, because a check that cannot fail is worth less than none.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# What a stack default looks like in application.yml, and what the test config must say instead.
RULES = (
    ("Kafka", re.compile(r"bootstrap-servers:.*localhost:19092"),
     (re.compile(r"^\s+bootstrap-servers:\s*localhost:1\s*$", re.M),
      re.compile(r"^\s+auto-startup:\s*false\s*$", re.M),
      re.compile(r"^\s+declare-topics:\s*false\s*$", re.M))),
    ("PostgreSQL", re.compile(r"url:.*jdbc:postgresql://localhost:15432"),
     (re.compile(r"^\s+url:\s*jdbc:postgresql://localhost:1/", re.M),)),
)


def problems(main_yml: str, test_yml: str | None) -> list[str]:
    found = []
    for name, uses_stack, required in RULES:
        if not uses_stack.search(main_yml):
            continue
        if test_yml is None:
            found.append(f"{name}: no src/test/resources/config/application.yml")
            continue
        for pattern in required:
            if not pattern.search(test_yml):
                found.append(f"{name}: test config does not have {pattern.pattern!r}")
    return found


def self_check():
    main = "    url: ${X:jdbc:postgresql://localhost:15432/db}\n    bootstrap-servers: ${Y:localhost:19092}\n"
    good = ("  datasource:\n    url: jdbc:postgresql://localhost:1/none\n  kafka:\n    bootstrap-servers: localhost:1\n"
            "    listener:\n      auto-startup: false\n  kafka:\n    declare-topics: false\n")
    assert problems(main, good) == [], problems(main, good)
    assert len(problems(main, None)) == 2
    assert problems(main, good.replace("localhost:1\n", "localhost:19092\n"))
    assert problems(main, good.replace("auto-startup: false", "auto-startup: true"))
    assert problems(main, good.replace("declare-topics: false", "declare-topics: true"))
    assert problems(main, good.replace("localhost:1/none", "localhost:15432/none"))
    assert problems("server:\n  port: 8080\n", None) == []


def main():
    self_check()
    failed = False
    for main_path in sorted(ROOT.glob("services/*/src/main/resources/application.yml")):
        module = main_path.parents[3]
        test_path = module / "src/test/resources/config/application.yml"
        test_yml = test_path.read_text(encoding="utf-8") if test_path.exists() else None
        found = problems(main_path.read_text(encoding="utf-8"), test_yml)
        print(f"{'FAIL' if found else 'ok  '}  {module.name}")
        for problem in found:
            print(f"        {problem}")
        failed |= bool(found)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
