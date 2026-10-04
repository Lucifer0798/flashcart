#!/usr/bin/env bash
# Validate and unit-test the alerting rules with promtool. Run by CI; runs locally with Docker.
#
#   infra/prometheus/test-rules.sh
#
# Two steps, against two copies of the rules:
#
#   1. `promtool check rules` on rules.yml as it is, which parses every expression and every
#      annotation template.
#   2. `promtool test rules` on a copy with the annotations removed. promtool compares annotations
#      exactly, so testing the real file would make every reworded description a failing test, and
#      the tests are about when a rule fires, not what it says.
#
# The image matches the one compose.yaml runs, so a rule that passes here is evaluated by the same
# engine in the stack. See ADR 0042.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
image="$(sed -n 's/^ *image: *\(prom\/prometheus:[^ ]*\).*/\1/p' "$here/../../compose.yaml" | head -1)"
if [ -z "$image" ]; then
  echo "could not find the prometheus image in compose.yaml" >&2
  exit 1
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/rules-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# python3 where it is real; on Windows that name is a Store shortcut that prints a hint and fails.
py=""
for candidate in python3 python; do
  if "$candidate" -c 'import yaml' > /dev/null 2>&1; then py="$candidate"; break; fi
done
if [ -z "$py" ]; then
  echo "need a python with PyYAML (pip install pyyaml)" >&2
  exit 1
fi

"$py" - "$here/rules.yml" "$work/rules.yml" <<'PYEOF'
import sys
import yaml

source, target = sys.argv[1], sys.argv[2]
with open(source, encoding="utf-8") as f:
    rules = yaml.safe_load(f)
for group in rules["groups"]:
    for rule in group["rules"]:
        rule.pop("annotations", None)
with open(target, "w", encoding="utf-8") as f:
    yaml.safe_dump(rules, f, sort_keys=False)
PYEOF
cp "$here/rules.test.yml" "$work/rules.test.yml"

# Git Bash on Windows rewrites /src-style arguments into Windows paths unless told not to, and Docker
# Desktop needs the host side of a mount as a Windows path. Both are no-ops on Linux.
mount() {
  if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}
export MSYS_NO_PATHCONV=1

echo "== promtool check rules ($image)"
docker run --rm -v "$(mount "$here"):/src:ro" --entrypoint promtool "$image" \
  check rules /src/rules.yml

echo "== promtool test rules"
docker run --rm -v "$(mount "$work"):/t:ro" -w /t --entrypoint promtool "$image" \
  test rules rules.test.yml
