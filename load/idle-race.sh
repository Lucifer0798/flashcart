#!/usr/bin/env bash
#
# Does the gateway send requests down connections the service is closing? See ADR 0076.
#
#   ./load/idle-race.sh [idle seconds ...]      # default: 50 59 60 61 70
#
# For each gap, fills the gateway's pool to the order service with 200 requests at once, leaves the connections
# idle for that long, then sends the same 200 again and counts the failures. The services' Tomcat closes an idle
# kept-alive connection after 60 seconds, so without the gateway's own idle limit a burst at 61 seconds was sent down
# connections as they closed. Order's _info endpoint is used because it is public and does no work of its own.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
GAPS=("$@")
[ ${#GAPS[@]} -gt 0 ] || GAPS=(50 59 60 61 70)

for idle in "${GAPS[@]}"; do
	START_AT=$(( ($(date +%s) + 5) * 1000 ))
	# MSYS_NO_PATHCONV, because Git Bash rewrites the container-side /scripts path into a Windows one.
	MSYS_NO_PATHCONV=1 docker run --rm -i --network flashcart_default -v "$ROOT/load/scripts:/scripts:ro" \
		-e VUS=200 -e IDLE="$idle" -e START_AT="$START_AT" \
		grafana/k6:0.56.0 run --quiet /scripts/idle-race.js 2>/dev/null | grep "^idle"
done
