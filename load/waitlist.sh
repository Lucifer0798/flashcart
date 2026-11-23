#!/usr/bin/env bash
#
# A sold-out item comes back, and the queue decides who gets it. See ADR 0078.
#
#   ./load/waitlist.sh [waiters] [units] [no-show-percent] [strangers]
#
# Seeds a sold-out SKU and that many waiters, who all join its waitlist together. Then the units land, and the queue
# tells the oldest waiters, holds a unit for each, and emails them. Those told check out at once, except the given
# share, who never come back, so their units have to lapse and pass down the line. Strangers who never queued try to
# buy the moment the stock lands.
#
# Then the books, across inventory, order and user: nobody told out of turn, nothing oversold, no stranger served
# while the queue still had takers, every told shopper who came back served, one email per shopper told and every one
# sent, and nothing left held.
#
# The inventory service is restarted with a short hold (WAITLIST_HOLD_TTL, default PT120S here instead of ten minutes),
# so unused holds lapse within the run. The setting is read back from the running service, and restored on the way
# out. Like load/checkout.sh, this leaves the orders and emails it made: they are each service's records.
set -euo pipefail

WAITERS="${1:-500}"
UNITS="${2:-100}"
NO_SHOW_PERCENT="${3:-10}"
STRANGERS="${4:-100}"
HOLD="${WAITLIST_HOLD_TTL:-PT120S}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUN="$(date +%s)"
PREFIX="load-waitlist-$RUN"
SKU="LOAD-WAITLIST-$RUN"
# Not a secret: it opens only the accounts this script makes.
PASSWORD="load-test-password-$RUN"
LABEL="waitlist-${WAITERS}-units-${UNITS}-noshow-${NO_SHOW_PERCENT}"
RESULTS="load/results"
mkdir -p "$RESULTS"

psql_in() { local db="$1"; shift; docker compose exec -T postgres psql -U flashcart -d "flashcart_$db" -X -q -t -A "$@"; }
dlq() {
	MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
		--topic flashcart.dlq 2>/dev/null | awk -F: '{sum += $3} END {print sum + 0}'
}
restart_inventory() {
	docker compose up -d --no-deps --no-build --force-recreate inventory > /dev/null 2>&1
	for i in $(seq 1 60); do [ "$(docker compose ps inventory --format '{{.Health}}')" = "healthy" ] && break; sleep 3; done
	# Through the gateway, which has to find the recreated container's new address (ADR 0075).
	for i in $(seq 1 30); do
		[ "$(curl -s -o /dev/null -w '%{http_code}' localhost:18080/api/v1/inventory/_info)" = "200" ] && return 0; sleep 2
	done
	echo "!!! inventory is not reachable through the gateway"; return 1
}
cleanup() {
	# Back to the ten-minute hold every other user of the stack expects.
	unset WAITLIST_HOLD_TTL
	restart_inventory > /dev/null 2>&1 || true
	echo "--> inventory back on its default hold"
}

echo "=============================================================="
echo " waiters=$WAITERS  units=$UNITS  no-shows=$NO_SHOW_PERCENT%  strangers=$STRANGERS  hold=$HOLD  run=$RUN"
echo "=============================================================="

# --- 1. inventory with a short hold, read back from the running service ------------------------------
export WAITLIST_HOLD_TTL="$HOLD"
trap cleanup EXIT
restart_inventory
ACTUAL=$(curl -s localhost:18080/api/v1/inventory/_info | grep -o '"waitlistHoldTtl":"[^"]*"' | cut -d'"' -f4)
# Compared as seconds: Java writes a sixty-second Duration as PT1M, not PT60S, so the strings need not match.
seconds() { python -c "import re,sys; m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?', sys.argv[1] or 'x'); print(-1 if not m else int(m.group(1) or 0)*3600 + int(m.group(2) or 0)*60 + int(m.group(3) or 0))" "$1"; }
HOLD_SECONDS=$(seconds "$HOLD")
if [ "$HOLD_SECONDS" -le 0 ] || [ "$(seconds "$ACTUAL")" != "$HOLD_SECONDS" ]; then
	echo "!!! asked for a $HOLD hold, inventory reports '${ACTUAL:-nothing}'; refusing to label a run with it"
	exit 1
fi
echo "--> inventory holds a told shopper's unit for $ACTUAL"

# --- 2. shoppers, a week old, and a sold-out SKU --------------------------------------------------------
SHOPPERS=$((WAITERS + STRANGERS))
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/users -H 'Content-Type: application/json' \
	-d "{\"email\":\"$PREFIX-0@example.test\",\"password\":\"$PASSWORD\",\"displayName\":\"Load template\"}")
[ "$CODE" = "201" ] || { echo "!!! could not register the template account (http $CODE)"; exit 1; }
psql_in user -v prefix="$PREFIX" -v shoppers="$SHOPPERS" <<'SQL'
insert into users (id, email, password_hash, display_name, created_at)
select gen_random_uuid(), :'prefix' || '-' || g || '@example.test',
       (select password_hash from users where email = :'prefix' || '-0@example.test'), 'Load ' || g,
       now() - interval '7 days'
  from generate_series(1, :shoppers) g;
SQL
WAREHOUSE=$(OPERATOR_EMAIL="warehouse@flashcart.local" ./scripts/operator-token.sh)
CATALOG=$(OPERATOR_EMAIL="catalog@flashcart.local" ./scripts/operator-token.sh)
CAT=$(curl -sf http://localhost:18080/api/v1/categories | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/products -H 'Content-Type: application/json' \
	-H "Authorization: Bearer $CATALOG" \
	-d "{\"sku\":\"$SKU\",\"name\":\"Waitlist probe $RUN\",\"categoryId\":\"$CAT\",\"basePrice\":25.00,\"currency\":\"USD\",\"status\":\"ACTIVE\"}")
[ "$CODE" = "201" ] || { echo "!!! could not create the product (http $CODE)"; exit 1; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/inventory/stock -H 'Content-Type: application/json' \
	-H "Authorization: Bearer $WAREHOUSE" -d "{\"sku\":\"$SKU\",\"initialQuantity\":0,\"reason\":\"waitlist load $RUN\"}")
[ "$CODE" = "201" ] || { echo "!!! could not create the sold-out stock (http $CODE)"; exit 1; }
echo "--> $SHOPPERS shoppers; $SKU sold out"

# --- 3. everyone queues, then the stock lands ---------------------------------------------------------
LEAD=$(( SHOPPERS / 100 + 15 ))
JOIN_AT=$(( ($(date +%s) + LEAD) * 1000 ))
RESTOCK_AT=$(( JOIN_AT + 15000 ))
# Long enough for every wave of lapsed holds to pass down the line, with the sweeper's ten seconds on each.
WATCH=$(( (HOLD_SECONDS + 15) * 5 + 60 ))
DLQ_BEFORE=$(dlq)
echo "--> k6: sign in, queue together in ${LEAD}s, units land 15s later, waiters watch for ${WATCH}s"
MSYS_NO_PATHCONV=1 docker run --rm -i \
	--network flashcart_default \
	-v "$ROOT/load/scripts:/scripts:ro" \
	-v "$ROOT/$RESULTS:/results" \
	-e GATEWAY=http://gateway:8080 -e PREFIX="$PREFIX" -e PASSWORD="$PASSWORD" -e SKU="$SKU" \
	-e WAITERS="$WAITERS" -e STRANGERS="$STRANGERS" -e NO_SHOW_PERCENT="$NO_SHOW_PERCENT" \
	-e JOIN_AT="$JOIN_AT" -e RESTOCK_AT="$RESTOCK_AT" -e WATCH_SECONDS="$WATCH" -e LABEL="$LABEL" \
	grafana/k6:0.56.0 run --quiet /scripts/waitlist.js \
	> "$RESULTS/$LABEL.txt" 2>&1 &
K6=$!

# The warehouse receives the units at RESTOCK_AT, from here rather than from k6, as a real delivery would arrive.
sleep $(( (RESTOCK_AT / 1000) - $(date +%s) ))
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:18080/api/v1/inventory/stock/$SKU/receive" \
	-H 'Content-Type: application/json' -H "Authorization: Bearer $WAREHOUSE" \
	-d "{\"quantity\":$UNITS,\"reason\":\"restock $RUN\"}")
echo "--> received $UNITS units (http $CODE)"
wait $K6 || true
[ -s "$RESULTS/$LABEL.json" ] || { echo "!!! k6 wrote no summary:"; tail -20 "$RESULTS/$LABEL.txt"; exit 1; }

# Settled: nothing held, no order in flight, every outbox sent, every email for this SKU sent or given up.
for i in $(seq 1 120); do
	HELD=$(psql_in inventory -c "select reserved from stock_items where sku = '$SKU'")
	FLIGHT=$(psql_in order -c "select count(*) from orders where idempotency_key like '$PREFIX-%' and status not in ('SHIPPED','CANCELLED')")
	MAIL=$(psql_in user -c "select count(*) from notices where kind = 'BACK_IN_STOCK' and payload->>'sku' = '$SKU' and status = 'PENDING'")
	[ "$HELD" = "0" ] && [ "$FLIGHT" = "0" ] && [ "$MAIL" = "0" ] && break
	sleep 5
done
DLQ_AFTER=$(dlq)

# --- 4. the books -----------------------------------------------------------------------------------------
set +e
python - "$RESULTS/$LABEL.json" "$PREFIX" "$SKU" "$UNITS" "$NO_SHOW_PERCENT" "$DLQ_BEFORE" "$DLQ_AFTER" "$ROOT" <<'PY' | tee "$RESULTS/$LABEL.books"
import json, subprocess, sys
k6 = json.load(open(sys.argv[1]))
prefix, sku, units, no_show, dlq_before, dlq_after, root = (sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]),
                                                            int(sys.argv[6]), int(sys.argv[7]), sys.argv[8])

def sql(db, query):
    out = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', 'flashcart', '-d', 'flashcart_' + db,
                          '-X', '-q', '-t', '-A', '-F', '|', '-c', query], capture_output=True, text=True, cwd=root)
    if out.returncode != 0:
        raise SystemExit(f'psql on {db} failed: {out.stderr[:300]}')
    return [line for line in out.stdout.splitlines() if line.strip()]

def counts(rows):
    return {k: int(v) for k, v in (row.split('|') for row in rows)}

entries = counts(sql('inventory', f"select status, count(*) from waitlist_entries where sku = '{sku}' group by 1"))
# Told out of turn: a waiter still WAITING who joined before somebody who was told.
out_of_turn = int(sql('inventory', f"""
    select count(*) from waitlist_entries w
     where w.sku = '{sku}' and w.status = 'WAITING'
       and (w.created_at, w.id) < (select created_at, id from waitlist_entries
                                    where sku = '{sku}' and status = 'NOTIFIED' order by created_at desc, id desc limit 1)""")[0])
on_hand, reserved = map(int, sql('inventory', f"select on_hand, reserved from stock_items where sku = '{sku}'")[0].split('|'))
holds = counts(sql('inventory', f"""
    select r.status, count(*) from reservations r join reservation_lines l on l.reservation_id = r.id
     where l.sku = '{sku}' and r.reservation_key like 'waitlist:%' group by 1"""))
orders = counts(sql('order', f"""
    select o.status, count(*) from orders o where o.idempotency_key like '{prefix}-%' group by 1"""))
emails = counts(sql('user', f"""
    select status || coalesce(':' || skip_reason, ''), count(*) from notices
     where kind = 'BACK_IN_STOCK' and payload->>'sku' = '{sku}' group by 1"""))
email_rows = sum(emails.values())
outbox = {db: int(sql(db, 'select count(*) from outbox_messages where published_at is null')[0])
          for db in ('order', 'payment', 'shipping', 'inventory', 'user')}

shipped = orders.get('SHIPPED', 0)
notified = entries.get('NOTIFIED', 0)
# Waiters who would come back if told: every waiter who joined, less the no-show share among the waiters.
no_show_waiters = sum(1 for i in range(1, k6['waiters'] + 1) if i % 100 < no_show)
showing = k6['joined'] - no_show_waiters
expected = min(units, showing)

print('--- what the shoppers saw, from k6 ---')
print(f"waiters joined:                {k6['joined']} of {k6['waiters']}   (failed: {k6['joinFailed']}, sign-in failed: {k6['signInFailed']})")
print(f"told it was back:              {k6['told']}   never told: {k6['notTold']}   no-shows among the told: {k6['noShow']}")
print(f"told and served:               {k6['toldShipped']}   cancelled: {k6['toldCancelled']}   refused: {k6['toldRefused']}   stuck: {k6['toldStuck']}")
print(f"strangers served:              {k6['strangerShipped']} of {k6['strangers']}   (cancelled {k6['strangerCancelled']}, other {k6['strangerOther']})")
for name, label in (('toldAfterMs', 'told after the restock ms'), ('shippedAfterToldMs', 'shipped after being told ms')):
    t = k6.get(name) or {}
    if t.get('max'):
        print(f"{label + ':':31}min {t['min']:.0f}  med {t['med']:.0f}  max {t['max']:.0f}")
print('--- the books ---')
print(f"waitlist:                      {dict(sorted(entries.items()))}   told out of turn: {out_of_turn}")
print(f"holds:                         {dict(sorted(holds.items()))}")
print(f"orders:                        {dict(sorted(orders.items()))}   expected shipped: {expected}")
print(f"stock:                         on_hand {on_hand}, reserved {reserved}   (restocked {units})")
print(f"back-in-stock emails:          {dict(sorted(emails.items()))}")
print(f"outbox unpublished:            {outbox}   dead letters added: {dlq_after - dlq_before}")

wrong = []
if k6['signInFailed'] or k6['joinFailed']:
    wrong.append(f"{k6['signInFailed']} could not sign in, {k6['joinFailed']} could not join the queue")
if out_of_turn:
    wrong.append(f'{out_of_turn} waiters still waiting joined before somebody who was told')
if shipped > units:
    wrong.append(f'OVERSOLD: {shipped} shipped from {units} units')
if shipped != expected:
    wrong.append(f'{shipped} shipped, expected {expected}: every unit should go while the queue has takers')
if expected == units and k6['strangerShipped']:
    wrong.append(f"{k6['strangerShipped']} strangers were served while the queue still had takers")
if k6['toldCancelled'] or k6['toldRefused'] or k6['toldStuck']:
    wrong.append('a shopper who was told and came back did not get the unit held for them')
if notified != k6['told']:
    wrong.append(f"{notified} entries told in the database, k6 saw {k6['told']}")
# One email per shopper told, and none dropped for any reason but going stale: the sender declines to say "held for
# you until" a time that has passed, which a short hold makes common and is how many it could not reach in time.
if email_rows != notified:
    wrong.append(f'{email_rows} back-in-stock emails ({emails}) for {notified} shoppers told')
odd = {k: v for k, v in emails.items() if k not in ('SENT', 'SKIPPED:STALE')}
if odd:
    wrong.append(f'back-in-stock emails neither sent nor stale: {odd}')
if holds.get('EXPIRED', 0) != k6['noShow']:
    wrong.append(f"{holds.get('EXPIRED', 0)} holds lapsed, but {k6['noShow']} told shoppers never came back")
if holds.get('ADOPTED', 0) != k6['toldShipped']:
    wrong.append(f"{holds.get('ADOPTED', 0)} holds taken over by a checkout, {k6['toldShipped']} told shoppers served")
if reserved != 0:
    wrong.append(f'{reserved} units still held')
if on_hand != units - shipped:
    wrong.append(f'on_hand {on_hand}, expected {units - shipped}')
if dlq_after != dlq_before:
    wrong.append(f'{dlq_after - dlq_before} messages dead-lettered')
if any(outbox.values()):
    wrong.append(f'outbox rows not yet published: {outbox}')
for problem in wrong:
    print(f'!!! {problem}')
print('BOOKS AGREE' if not wrong else 'BOOKS DISAGREE')
sys.exit(1 if wrong else 0)
PY
BOOKS=${PIPESTATUS[0]}
set -e
exit "$BOOKS"
