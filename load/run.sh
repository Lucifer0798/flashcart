#!/usr/bin/env bash
#
# One flash-sale experiment: restart inventory with a given configuration, seed a scarce SKU, let a
# few hundred virtual shoppers fight over it, then check the books.
#
#   ./load/run.sh <ATOMIC_UPDATE|PESSIMISTIC_LOCK> <gate true|false> [stock] [vus] [iterations]
#
# The result that matters is not in the k6 summary. It is the last section: whether the number of
# granted reservations equals the stock that existed. Everything else is throughput trivia beside
# the question this platform is built to answer.
set -euo pipefail

STRATEGY="${1:-ATOMIC_UPDATE}"
GATE="${2:-true}"
STOCK="${3:-100}"
VUS="${4:-200}"
ITERATIONS="${5:-2000}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKU="LOAD-$(date +%s)"
LABEL="${STRATEGY}-gate-${GATE}"
RESULTS="load/results"
mkdir -p "$RESULTS"

echo "=============================================================="
echo " strategy=$STRATEGY  gate=$GATE  stock=$STOCK  vus=$VUS  attempts=$ITERATIONS"
echo "=============================================================="

# --- 1. put inventory into the configuration under test -----------------------------------------
#
# Only inventory restarts. Postgres, Redis, Kafka and the other services stay warm, so the JIT and
# the connection pools elsewhere are not part of what is being compared.
echo "--> restarting inventory"
RESERVATION_STRATEGY="$STRATEGY" AVAILABILITY_GATE_ENABLED="$GATE" \
	docker compose up -d --force-recreate --no-deps inventory > /dev/null 2>&1

until [ "$(docker compose ps inventory --format '{{.Health}}')" = "healthy" ]; do sleep 2; done

# Read the settings back out of the running service rather than trusting the variables above. A run
# labelled with the configuration it was asked for, rather than the one it got, is worse than no
# run at all -- it is a number that will be quoted later.
INFO=$(docker compose exec -T inventory wget -qO- http://localhost:8085/api/v1/inventory/_info)
ACTUAL_STRATEGY=$(echo "$INFO" | grep -o '"reservationStrategy":"[A-Z_]*"' | cut -d'"' -f4)
echo "--> inventory reports strategy=${ACTUAL_STRATEGY:-unreadable}"

# Failing when the value cannot be read at all, not just when it disagrees. A guard that skips
# itself when its own parse returns nothing is decorative -- and the field really is called
# reservationStrategy, so a pattern looking for "strategy" matches nothing and waves everything
# through.
if [ -z "$ACTUAL_STRATEGY" ]; then
	echo "!!! could not read the strategy back from _info; not recording an unlabelled run"
	echo "    response was: $INFO"
	exit 1
fi
if [ "$ACTUAL_STRATEGY" != "$STRATEGY" ]; then
	echo "!!! asked for $STRATEGY, service is running $ACTUAL_STRATEGY -- refusing to record this"
	exit 1
fi

ACTUAL_GATE=$(echo "$INFO" | grep -o '"availabilityGate":"[a-z]*"' | cut -d'"' -f4)
echo "--> inventory reports gate=${ACTUAL_GATE:-unreadable}"
if [ -z "$ACTUAL_GATE" ] || [ "$ACTUAL_GATE" != "$GATE" ]; then
	echo "!!! asked for gate=$GATE, service reports '${ACTUAL_GATE:-unreadable}' -- refusing to record this"
	exit 1
fi

# --- 2. seed exactly the stock we intend to fight over -------------------------------------------
echo "--> seeding $SKU with $STOCK units"
curl -sf -X POST http://localhost:18080/api/v1/inventory/stock \
	-H 'Content-Type: application/json' \
	-d "{\"sku\":\"$SKU\",\"initialQuantity\":$STOCK,\"reason\":\"load test $LABEL\"}" > /dev/null

# --- 3. the stampede ------------------------------------------------------------------------------
echo "--> running k6"
# MSYS_NO_PATHCONV, because Git Bash rewrites the container-side /scripts path into a Windows one
# and k6 then looks for the script under C:/Program Files/Git.
MSYS_NO_PATHCONV=1 docker run --rm -i \
	--network flashcart_default \
	-v "$ROOT/load/scripts:/scripts:ro" \
	-e GATEWAY=http://gateway:8080 \
	-e SKU="$SKU" \
	-e VUS="$VUS" \
	-e ITERATIONS="$ITERATIONS" \
	grafana/k6:0.56.0 run /scripts/flash-sale.js \
	2>&1 | tee "$RESULTS/${LABEL}.txt" || true

# --- 4. check the books ---------------------------------------------------------------------------
#
# This is the assertion the whole phase exists for, and it is deliberately made against PostgreSQL
# rather than against k6's own tally: the load tool counts what it was told, the database holds what
# is true.
RESERVED=$(docker compose exec -T postgres psql -U flashcart -d flashcart_inventory -tAc \
	"select reserved from stock_items where sku = '$SKU'" | tr -d '[:space:]')
AVAILABLE=$(docker compose exec -T postgres psql -U flashcart -d flashcart_inventory -tAc \
	"select on_hand - reserved from stock_items where sku = '$SKU'" | tr -d '[:space:]')
HELD=$(docker compose exec -T postgres psql -U flashcart -d flashcart_inventory -tAc \
	"select count(*) from reservations r join reservation_lines l on l.reservation_id = r.id
	  where l.sku = '$SKU' and r.status = 'HELD'" | tr -d '[:space:]')

{
	echo ""
	echo "--- the books, from PostgreSQL ---"
	echo "stock seeded:        $STOCK"
	echo "reserved:            $RESERVED"
	echo "available:           $AVAILABLE"
	echo "reservations HELD:   $HELD"
} | tee -a "$RESULTS/${LABEL}.txt"

# k6's own tally must agree with the books. It is not a duplicate of the check below: that one asks
# whether the platform oversold, this one asks whether the measurement can be trusted at all.
#
# Both earlier versions of the key generator produced repeated reservationKeys, and because the
# service is correctly idempotent on that key, every repeat came back 201 and was counted as a sale.
# One run claimed 424 grants against 100 units. The platform was right every time; the harness was
# not, and without this line the phase would have published four confident numbers built on it.
GRANTED=$(grep -oE 'reservations_granted[. ]*: *[0-9]+' "$RESULTS/${LABEL}.txt" | grep -oE '[0-9]+$' || true)
if [ -n "$GRANTED" ] && [ "$GRANTED" != "$RESERVED" ]; then
	echo "!!! HARNESS DISAGREES WITH THE LEDGER: k6 counted $GRANTED grants, PostgreSQL holds $RESERVED" 		| tee -a "$RESULTS/${LABEL}.txt"
	echo "    Almost certainly duplicate reservationKeys being answered idempotently." 		| tee -a "$RESULTS/${LABEL}.txt"
	exit 1
fi

if [ "$RESERVED" != "$STOCK" ] || [ "$AVAILABLE" != "0" ]; then
	echo "!!! OVERSOLD OR UNDERSOLD: expected reserved=$STOCK available=0" | tee -a "$RESULTS/${LABEL}.txt"
	exit 1
fi
echo "OK: exactly $STOCK units were sold, out of $ITERATIONS attempts" | tee -a "$RESULTS/${LABEL}.txt"
