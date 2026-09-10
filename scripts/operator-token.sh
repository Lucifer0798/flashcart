#!/usr/bin/env bash
#
# Prints an operator access token, or fails loudly.
#
#   TOKEN=$(./scripts/operator-token.sh)
#
# Shared by the load harness, the chaos harness and anything else that needs to seed stock, because
# three copies of a sign-in is three places to forget the same fix. The credentials are the seeded
# development operator from the user service's V2 migration -- see that file for why a checked-in
# credential is acceptable here and where it stops being acceptable.
set -euo pipefail

G="${GATEWAY_URL:-http://localhost:18080}"
EMAIL="${OPERATOR_EMAIL:-operator@flashcart.local}"
PASSWORD="${OPERATOR_PASSWORD:-operator-development-password}"

RESPONSE=$(curl -s --max-time 10 -w '\n%{http_code}' -X POST "$G/api/v1/users/sessions" \
	-H 'Content-Type: application/json' \
	-d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")

CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

# Checked rather than assumed, and reported to stderr so the caller's $(...) still captures only the
# token. A harness that silently proceeds without one produces 401s three layers away that read as a
# platform fault -- which has already happened here more than once.
if [ "$CODE" != "200" ]; then
	echo "could not sign in as $EMAIL (http $CODE): $BODY" >&2
	exit 1
fi

TOKEN=$(echo "$BODY" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
	echo "signed in as $EMAIL but found no accessToken in: $BODY" >&2
	exit 1
fi

echo "$TOKEN"
