#!/usr/bin/env bash
#
# A sale opens and everyone signs in at once. See ADR 0071.
#
#   ./load/sign-in.sh [shoppers] [typo-percent]
#
# Seeds that many accounts, then has every one of them sign in at the same moment and refresh once. A share of
# them mistype their password first. They all arrive from one address, as people behind one office or mobile
# carrier do. The result that matters is how many shoppers with the right password got in, and the last section
# checks that against PostgreSQL rather than trusting k6's tally.
#
# Everything it creates is deleted at the end, including the failures it recorded: they would otherwise hold the
# per-client limit against the next run for fifteen minutes.
set -euo pipefail

SHOPPERS="${1:-1000}"
TYPO_PERCENT="${2:-0}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUN="$(date +%s)"
PREFIX="load-signin-$RUN"
# Not a secret: it opens only the accounts this script makes and then deletes.
PASSWORD="load-test-password-$RUN"
LABEL="sign-in-${SHOPPERS}-typos-${TYPO_PERCENT}"
RESULTS="load/results"
mkdir -p "$RESULTS"

psql_user() { docker compose exec -T postgres psql -U flashcart -d flashcart_user -X -q -t -A "$@"; }

echo "=============================================================="
echo " shoppers=$SHOPPERS  typos=$TYPO_PERCENT%  run=$RUN"
echo "=============================================================="

# --- 1. what the user service is actually running with ----------------------------------------------
#
# The limits come from the defaults unless the environment overrides them. A run labelled with defaults it did
# not have would be quoted later as if it had.
OVERRIDES=$(docker compose exec -T user env | grep -E '^FLASHCART_SIGN_IN|^DB_POOL_SIZE' || true)
if [ -n "$OVERRIDES" ]; then
	echo "!!! the user service has sign-in or pool overrides; this harness records defaults only:"
	echo "$OVERRIDES"
	exit 1
fi
echo "--> user service: default limits (30 failures per client per 15 min) and pool size (10)"

# Armed before anything is created, so a run that fails halfway leaves nothing behind either.
SAMPLER=""
STARTED=""
cleanup() {
	if [ -n "$SAMPLER" ]; then kill "$SAMPLER" 2>/dev/null || true; fi
	psql_user -v prefix="$PREFIX" -v started="${STARTED:-infinity}" <<'SQL' || true
delete from refresh_tokens where user_id in (select id from users where email like :'prefix' || '-%');
delete from users where email like :'prefix' || '-%';
delete from throttled_attempts where attempted_at >= :'started'::timestamptz;
SQL
	echo "--> removed the run's accounts, sessions and failure rows"
}
trap cleanup EXIT

# --- 2. accounts, all with one real BCrypt hash -------------------------------------------------------
#
# One account through the API, so the hash is exactly what registration makes, at the cost it uses. The rest
# copy it in SQL: registering them all through BCrypt would take minutes and measure nothing.
TEMPLATE="$PREFIX-0@example.test"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/users \
	-H 'Content-Type: application/json' \
	-d "{\"email\":\"$TEMPLATE\",\"password\":\"$PASSWORD\",\"displayName\":\"Load template\"}")
if [ "$CODE" != "201" ]; then
	echo "!!! could not register the template account (http $CODE)"
	exit 1
fi

psql_user -v prefix="$PREFIX" -v shoppers="$SHOPPERS" <<'SQL'
insert into users (id, email, password_hash, display_name)
select gen_random_uuid(), :'prefix' || '-' || g || '@example.test',
       (select password_hash from users where email = :'prefix' || '-0@example.test'), 'Load ' || g
  from generate_series(1, :shoppers) g;
SQL
SEEDED=$(psql_user -v prefix="$PREFIX" <<'SQL' | tr -d '[:space:]'
select count(*) from users where email like :'prefix' || '-%';
SQL
)
if [ "$SEEDED" != "$((SHOPPERS + 1))" ]; then
	echo "!!! seeded $SEEDED accounts, expected $((SHOPPERS + 1))"
	exit 1
fi
echo "--> seeded $SHOPPERS accounts"

STARTED=$(psql_user -c "select now()" | tr -d '\r')

# --- 3. the sale opens ----------------------------------------------------------------------------
#
# The user service's CPU, sampled while k6 runs: BCrypt is meant to cost CPU, so that is where it should show.
CPU_LOG="$RESULTS/$LABEL.cpu"
: > "$CPU_LOG"
(
	while true; do
		docker stats --no-stream --format '{{.CPUPerc}}' flashcart-user-1 2>/dev/null >> "$CPU_LOG" || true
	done
) &
SAMPLER=$!

echo "--> running k6"
# MSYS_NO_PATHCONV, because Git Bash rewrites the container-side paths into Windows ones.
MSYS_NO_PATHCONV=1 docker run --rm -i \
	--network flashcart_default \
	-v "$ROOT/load/scripts:/scripts:ro" \
	-v "$ROOT/$RESULTS:/results" \
	-e GATEWAY=http://gateway:8080 \
	-e PREFIX="$PREFIX" \
	-e SHOPPERS="$SHOPPERS" \
	-e TYPO_PERCENT="$TYPO_PERCENT" \
	-e PASSWORD="$PASSWORD" \
	-e LABEL="$LABEL" \
	grafana/k6:0.56.0 run --quiet /scripts/sign-in.js \
	> "$RESULTS/$LABEL.txt" 2>&1 || true
kill "$SAMPLER" 2>/dev/null || true
SAMPLER=""

if [ ! -s "$RESULTS/$LABEL.json" ]; then
	echo "!!! k6 wrote no summary; its output was:"
	tail -20 "$RESULTS/$LABEL.txt"
	exit 1
fi

# --- 4. the books ---------------------------------------------------------------------------------
#
# Every successful sign-in starts a session family, and every successful refresh adds a token to one. Every
# refused typo writes one failure row per limit, three; a throttled attempt writes none, because it is refused
# before the password is looked at; and a typist who then gets in clears their own one-account-one-client row.
SESSIONS=$(psql_user -v prefix="$PREFIX" -v started="$STARTED" <<'SQL' | tr -d '[:space:]'
select count(distinct t.family_id) || '/' || count(*)
  from refresh_tokens t join users u on u.id = t.user_id
 where u.email like :'prefix' || '-%' and t.issued_at >= :'started';
SQL
)
# A separator that survives the whitespace strip: a space did not, and "200 400" read as 200400 twice.
FAMILIES="${SESSIONS%%/*}"
TOKENS="${SESSIONS##*/}"
FAILURE_ROWS=$(psql_user -v started="$STARTED" <<'SQL' | tr -d '[:space:]'
select count(*) from throttled_attempts where attempted_at >= :'started';
SQL
)
PEAK_CPU=$(tr -d '%' < "$CPU_LOG" | sort -n | tail -1)

set +e
python - "$RESULTS/$LABEL.json" "$FAMILIES" "$TOKENS" "$FAILURE_ROWS" "${PEAK_CPU:-unknown}" <<'PY' | tee "$RESULTS/$LABEL.books"
import json, sys
k6 = json.load(open(sys.argv[1]))
families, tokens, failure_rows, peak_cpu = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
typed_right = k6["shoppers"]
print("--- what the shoppers saw, from k6 ---")
print(f"signed in:                     {k6['signInOk']} of {typed_right}")
print(f"right password refused (429):  {k6['rightPasswordThrottled']}")
print(f"shed (503):                    {k6['shed']}")
print(f"errors:                        {k6['errored']}")
print(f"typos refused (401):           {k6['typoRefused']}")
print(f"typos throttled (429):         {k6['typoThrottled']}")
print(f"refreshed:                     {k6['refreshOk']}  (errors {k6['refreshErrored']})")
s, r = k6["signInMs"], k6["refreshMs"]
if s:
    print(f"sign-in ms:                    med {s['med']:.0f}  p95 {s['p(95)']:.0f}  p99 {s['p(99)']:.0f}  max {s['max']:.0f}")
if r:
    print(f"refresh ms:                    med {r['med']:.0f}  p95 {r['p(95)']:.0f}  max {r['max']:.0f}")
print(f"whole run:                     {k6['durationMs'] / 1000:.1f} s")
print(f"user service peak CPU:         {peak_cpu}%  (100% is one core)")
print("--- the books, from PostgreSQL ---")
print(f"sessions started:              {families}")
print(f"tokens issued:                 {tokens}   (sign-ins + refreshes = {k6['signInOk'] + k6['refreshOk']})")
expected_rows = 3 * k6["typoRefused"] - k6["typoThenSignedIn"]
print(f"failure rows:                  {failure_rows}   (3 per typo refused, less 1 per typist who then got in = {expected_rows})")
wrong = []
if families != k6["signInOk"]:
    wrong.append("sessions in the database do not match the sign-ins k6 counted")
if tokens != k6["signInOk"] + k6["refreshOk"]:
    wrong.append("tokens in the database do not match sign-ins plus refreshes")
if failure_rows != expected_rows:
    wrong.append("failure rows do not match the typos refused (another client signing in wrongly during the run?)")
accounted = k6["signInOk"] + k6["rightPasswordThrottled"] + k6["shed"] + k6["errored"]
if accounted != typed_right:
    wrong.append(f"k6 accounted for {accounted} shoppers out of {typed_right}")
for problem in wrong:
    print(f"!!! {problem}")
print("BOOKS AGREE" if not wrong else "BOOKS DISAGREE")
sys.exit(1 if wrong else 0)
PY
BOOKS=${PIPESTATUS[0]}
set -e

# --- 5. cleanup() removes the run's accounts, sessions and failure rows on the way out ----------------
exit "$BOOKS"
