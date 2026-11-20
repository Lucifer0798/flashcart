#!/usr/bin/env bash
#
# A sale opens and everyone checks out at once. See ADR 0076.
#
#   ./load/checkout.sh [shoppers] [stock]
#
# Seeds that many shoppers and one product with that much stock. Every shopper signs in, then all of them place
# an order for one unit at the same moment, and each watches it until it settles: SHIPPED, or CANCELLED when the
# stock ran out. Then every service's books are read and held against each other. Nothing may be oversold, nothing
# may be stuck halfway through the saga, and the orders, payments, parcels and stock must agree with each other and
# with what the shoppers were told.
#
# Unlike load/sign-in.sh, this leaves what it made: orders, payments and parcels are each service's own records,
# with history, outbox rows and emails behind them, and deleting across five databases would be its own risk. All
# of it is tagged with the run: shoppers load-checkout-<run>-N, the SKU LOAD-CHECKOUT-<run>.
set -euo pipefail

SHOPPERS="${1:-1000}"
STOCK="${2:-100}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RUN="$(date +%s)"
PREFIX="load-checkout-$RUN"
SKU="LOAD-CHECKOUT-$RUN"
# Not a secret: it opens only the accounts this script makes.
PASSWORD="load-test-password-$RUN"
# Any price but .13 (declined) or .99 (provider timeout): see GET /api/v1/payment/_info.
PRICE="25.00"
LABEL="checkout-${SHOPPERS}-stock-${STOCK}"
RESULTS="load/results"
mkdir -p "$RESULTS"

psql_in() { local db="$1"; shift; docker compose exec -T postgres psql -U flashcart -d "flashcart_$db" -X -q -t -A "$@"; }
dlq() {
	MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
		--topic flashcart.dlq 2>/dev/null | awk -F: '{sum += $3} END {print sum + 0}'
}

echo "=============================================================="
echo " shoppers=$SHOPPERS  stock=$STOCK  run=$RUN"
echo "=============================================================="

# --- 1. accounts, all with one real BCrypt hash, a week old ---------------------------------------------
TEMPLATE="$PREFIX-0@example.test"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/users \
	-H 'Content-Type: application/json' \
	-d "{\"email\":\"$TEMPLATE\",\"password\":\"$PASSWORD\",\"displayName\":\"Load template\"}")
[ "$CODE" = "201" ] || { echo "!!! could not register the template account (http $CODE)"; exit 1; }
psql_in user -v prefix="$PREFIX" -v shoppers="$SHOPPERS" <<'SQL'
insert into users (id, email, password_hash, display_name, created_at)
select gen_random_uuid(), :'prefix' || '-' || g || '@example.test',
       (select password_hash from users where email = :'prefix' || '-0@example.test'), 'Load ' || g,
       now() - interval '7 days'
  from generate_series(1, :shoppers) g;
SQL
echo "--> seeded $SHOPPERS shoppers"

# --- 2. one product, priced to be approved, with exactly the stock under test ----------------------------
WAREHOUSE=$(OPERATOR_EMAIL="warehouse@flashcart.local" ./scripts/operator-token.sh)
CATALOG=$(OPERATOR_EMAIL="catalog@flashcart.local" ./scripts/operator-token.sh)
CAT=$(curl -sf http://localhost:18080/api/v1/categories | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/products -H 'Content-Type: application/json' \
	-H "Authorization: Bearer $CATALOG" \
	-d "{\"sku\":\"$SKU\",\"name\":\"Checkout probe $RUN\",\"categoryId\":\"$CAT\",\"basePrice\":$PRICE,\"currency\":\"USD\",\"status\":\"ACTIVE\"}")
[ "$CODE" = "201" ] || { echo "!!! could not create the product (http $CODE)"; exit 1; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/api/v1/inventory/stock -H 'Content-Type: application/json' \
	-H "Authorization: Bearer $WAREHOUSE" -d "{\"sku\":\"$SKU\",\"initialQuantity\":$STOCK,\"reason\":\"checkout load $RUN\"}")
[ "$CODE" = "201" ] || { echo "!!! could not seed stock (http $CODE)"; exit 1; }
echo "--> $SKU: $STOCK units at $PRICE"

# --- 3. the sale opens -------------------------------------------------------------------------------
# Long enough for every shopper to sign in first (about 150 a second here, ADR 0071), so the orders arrive together.
LEAD=$(( SHOPPERS / 100 + 15 ))
START_AT=$(( ($(date +%s) + LEAD) * 1000 ))
DLQ_BEFORE=$(dlq)
echo "--> running k6: everyone signs in, then checks out together in ${LEAD}s"
MSYS_NO_PATHCONV=1 docker run --rm -i \
	--network flashcart_default \
	-v "$ROOT/load/scripts:/scripts:ro" \
	-v "$ROOT/$RESULTS:/results" \
	-e GATEWAY=http://gateway:8080 -e PREFIX="$PREFIX" -e PASSWORD="$PASSWORD" -e SKU="$SKU" \
	-e SHOPPERS="$SHOPPERS" -e START_AT="$START_AT" -e LABEL="$LABEL" \
	grafana/k6:0.56.0 run --quiet /scripts/checkout.js \
	> "$RESULTS/$LABEL.txt" 2>&1 || true
[ -s "$RESULTS/$LABEL.json" ] || { echo "!!! k6 wrote no summary:"; tail -20 "$RESULTS/$LABEL.txt"; exit 1; }
# A few seconds for anything still on the bus to land, so a late answer is not reported as stuck.
sleep 10
DLQ_AFTER=$(dlq)

# --- 4. the books, from every service ----------------------------------------------------------------
set +e
python - "$RESULTS/$LABEL.json" "$PREFIX" "$SKU" "$STOCK" "$DLQ_BEFORE" "$DLQ_AFTER" "$ROOT" <<'PY' | tee "$RESULTS/$LABEL.books"
import json, subprocess, sys
k6 = json.load(open(sys.argv[1]))
prefix, sku, stock, dlq_before, dlq_after, root = sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), sys.argv[7]

def sql(db, query, stdin_rows=None):
    script = ''
    if stdin_rows is not None:
        script = 'create temp table run_orders (n text);\ncopy run_orders from stdin;\n' + '\n'.join(stdin_rows) + '\n\\.\n'
    script += query
    out = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', 'flashcart', '-d', 'flashcart_' + db,
                          '-X', '-q', '-t', '-A', '-F', '|'], input=script, capture_output=True, text=True, cwd=root)
    if out.returncode != 0:
        raise SystemExit(f'psql on {db} failed: {out.stderr[:300]}')
    return [line for line in out.stdout.splitlines() if line.strip()]

orders = sql('order', f"select order_number, status from orders where idempotency_key like '{prefix}-%'")
statuses = {}
for row in orders:
    number, status = row.split('|')
    statuses[status] = statuses.get(status, 0) + 1
numbers = [row.split('|')[0] for row in orders]
db_shipped = statuses.get('SHIPPED', 0)
db_cancelled = statuses.get('CANCELLED', 0)
in_flight = {s: n for s, n in statuses.items() if s not in ('SHIPPED', 'CANCELLED')}

payments = dict(row.split('|') for row in sql('payment',
    'select p.status, count(*) from payments p join run_orders r on r.n = p.order_number group by 1;', numbers))
payments = {k: int(v) for k, v in payments.items()}
shipments = int(sql('shipping', 'select count(*) from shipments s join run_orders r on r.n = s.order_number;', numbers)[0])
on_hand, reserved = map(int, sql('inventory', f"select on_hand, reserved from stock_items where sku = '{sku}'")[0].split('|'))
reservations = dict(row.split('|') for row in sql('inventory', f"""
    select r.status, count(*) from reservations r join reservation_lines l on l.reservation_id = r.id
     where l.sku = '{sku}' group by 1"""))
reservations = {k: int(v) for k, v in reservations.items()}
outbox = {db: int(sql(db, 'select count(*) from outbox_messages where published_at is null')[0])
          for db in ('order', 'payment', 'shipping', 'inventory')}

expected = min(k6['placed'], stock)
print('--- what the shoppers saw, from k6 ---')
print(f"signed in and waiting:         {k6['shoppers'] - k6['signInFailed']} of {k6['shoppers']}   (late to the start: {k6['late']})")
print(f"orders placed (202):           {k6['placed']}   refused at placing: {k6['placeRefused']}")
print(f"settled SHIPPED:               {k6['shipped']}")
print(f"settled CANCELLED:             {k6['cancelled']}")
print(f"still unsettled after 120s:    {k6['stuck']}   (failed polls: {k6['pollErrors']})")
for name, label in (('placeMs', 'placing ms'), ('shippedMs', 'to SHIPPED ms'), ('cancelledMs', 'to CANCELLED ms')):
    t = k6.get(name) or {}
    if t.get('max'):
        print(f"{label + ':':31}med {t['med']:.0f}  p95 {t['p(95)']:.0f}  max {t['max']:.0f}")
print(f"whole run:                     {k6['durationMs'] / 1000:.1f} s")
print('--- the books ---')
print(f"orders:                        {dict(sorted(statuses.items()))}")
print(f"payments:                      {dict(sorted(payments.items()))}")
print(f"shipments:                     {shipments}")
print(f"stock:                         on_hand {on_hand}, reserved {reserved}   (seeded {stock})")
print(f"reservations:                  {dict(sorted(reservations.items()))}")
print(f"outbox unpublished:            {outbox}")
print(f"dead letters added:            {dlq_after - dlq_before}")

wrong = []
if db_shipped > stock:
    wrong.append(f'OVERSOLD: {db_shipped} shipped from {stock} units')
if db_shipped != expected:
    wrong.append(f'{db_shipped} shipped, expected {expected}: every unit should sell while there are buyers')
if in_flight:
    wrong.append(f'orders still mid-saga: {in_flight}')
# A shopper who could not sign in, or whose order was refused outright, was turned away, whatever the books of the
# orders that did get through say (ADR 0073). Without this, a run in which most shoppers never reached the shop
# reported its books in agreement, because they were: about the few who did.
if k6['signInFailed']:
    wrong.append(f"{k6['signInFailed']} shoppers could not sign in")
if k6['placeRefused']:
    wrong.append(f"{k6['placeRefused']} orders refused at placing")
if k6['stuck']:
    wrong.append(f"{k6['stuck']} shoppers gave up waiting: their orders had not settled after two minutes")
if len(orders) != k6['placed']:
    wrong.append(f"{len(orders)} orders in the database, k6 placed {k6['placed']}")
if db_shipped != k6['shipped'] or db_cancelled != k6['cancelled']:
    wrong.append('the shoppers were told something other than what the order service holds')
if payments.get('COMPLETED', 0) != db_shipped or sum(payments.values()) != db_shipped:
    wrong.append(f'payments {payments} do not match {db_shipped} shipped orders')
if shipments != db_shipped:
    wrong.append(f'{shipments} parcels for {db_shipped} shipped orders')
if reserved != 0:
    wrong.append(f'{reserved} units still held')
if on_hand != stock - db_shipped:
    wrong.append(f'on_hand {on_hand}, expected {stock - db_shipped}')
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
