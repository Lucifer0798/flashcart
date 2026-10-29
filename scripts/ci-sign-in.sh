# Sourced, not run, by every CI step that needs a token:
#
#   . scripts/ci-sign-in.sh
#
# Signs in afresh and sets OPERATOR, WAREHOUSE, CATALOG, SUPPORT and TOKEN (the CI shopper) for this
# step alone. Each step signs in again rather than reusing tokens from the start of the job, because an
# access token lasts fifteen minutes (ADR 0059) and the job is long enough to outlive one. The day it
# did, every later step would fail with a 401 that looked like a platform fault.
#
# The shopper is the one account created by the "Sign in, because a checkout now needs a shopper" step,
# which leaves its address and password, not a token, in CI_SHOPPER_EMAIL and CI_SHOPPER_PASSWORD. Signing
# in successfully is not counted by the sign-in throttle (ADR 0058), so doing it per step costs only
# BCrypt's time.

G="${GATEWAY_URL:-http://localhost:18080}"

OPERATOR=$(./scripts/operator-token.sh)
WAREHOUSE=$(OPERATOR_EMAIL="warehouse@flashcart.local" ./scripts/operator-token.sh)
CATALOG=$(OPERATOR_EMAIL="catalog@flashcart.local" ./scripts/operator-token.sh)
SUPPORT=$(OPERATOR_EMAIL="support@flashcart.local" ./scripts/operator-token.sh)

if [ -n "${CI_SHOPPER_EMAIL:-}" ]; then
	TOKEN=$(curl -s --max-time 10 -X POST "$G/api/v1/users/sessions" -H 'Content-Type: application/json' \
		-d "{\"email\":\"$CI_SHOPPER_EMAIL\",\"password\":\"$CI_SHOPPER_PASSWORD\"}" \
		| grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
	if [ -z "$TOKEN" ]; then
		echo "could not sign the CI shopper in again as $CI_SHOPPER_EMAIL" >&2
		return 1 2>/dev/null || exit 1
	fi
fi

export G OPERATOR WAREHOUSE CATALOG SUPPORT TOKEN
