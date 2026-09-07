#!/usr/bin/env bash
#
# Failure injection.
#
#   ./chaos/inject.sh [scenario]
#
# Every scenario below breaks something real and then asserts what must still be true. The assertions
# are the point: this is not a demonstration that things fall over, it is a check that the platform
# fails the way its ADRs claim it does.
#
# The shared rule across all of them: **nothing may oversell, and nothing may be lost**. A scenario
# may legitimately refuse buyers, stall a saga, or return 503. It may not sell the same unit twice or
# swallow a message that was committed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
G=http://localhost:18080
SCENARIO="${1:-all}"
FAILURES=0

say()  { echo ""; echo "=== $* ==="; }
step() { echo "  -> $*"; }
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; FAILURES=$((FAILURES + 1)); }

psql_i() { docker compose exec -T postgres psql -U flashcart -d flashcart_inventory -tAc "$1" | tr -d '[:space:]'; }
psql_o() { docker compose exec -T postgres psql -U flashcart -d flashcart_order -tAc "$1" | tr -d '[:space:]'; }

seed() { # sku, qty -> stock plus a priced product so orders can be placed
	local sku="$1" qty="$2"
	local cat code
	cat=$(curl -sf --max-time 10 $G/api/v1/categories | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
	curl -sf --max-time 10 -X POST $G/api/v1/inventory/stock -H 'Content-Type: application/json' \
		-d "{\"sku\":\"$sku\",\"initialQuantity\":$qty,\"reason\":\"chaos\"}" > /dev/null

	# The product name carries the sku, and the status is checked rather than assumed.
	#
	# Catalog derives a slug from the NAME and enforces uniqueness on it, so a constant "Chaos probe"
	# collides with the previous scenario's product and returns 409. With curl -sf swallowing that,
	# the order which follows 404s on a sku that has no price -- and the run reads as "the platform
	# lost an order under chaos" when nothing was wrong except the harness. That is the same mistake
	# the load harness made with reservationKey in Phase 10, wearing a different costume.
	code=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X POST $G/api/v1/products \
		-H 'Content-Type: application/json' \
		-d "{\"sku\":\"$sku\",\"name\":\"Chaos probe $sku\",\"categoryId\":\"$cat\",\"basePrice\":19.00,\"currency\":\"USD\",\"status\":\"ACTIVE\"}")
	if [ "$code" != "201" ]; then
		fail "could not seed a product for $sku (http $code) -- the scenario below would be measuring the seed, not the platform"
		return 1
	fi
}

place() { # sku, qty -> order number
	curl -s --max-time 15 -X POST $G/api/v1/orders -H 'Content-Type: application/json' \
		-d "{\"idempotencyKey\":\"chaos-$(date +%s%N)\",\"customerId\":\"chaos\",\"lines\":[{\"sku\":\"$1\",\"quantity\":$2}]}" \
		| sed -n 's/.*"orderNumber":"\([^"]*\)".*/\1/p'
}

status_of() { curl -sf --max-time 10 "$G/api/v1/orders/$1" | sed -n 's/.*"status":"\([A-Z_]*\)".*/\1/p'; }

settle() { # order, seconds -> final status, or the last one seen
	local order="$1" limit="${2:-60}" i s
	for ((i = 0; i < limit; i += 2)); do
		s=$(status_of "$order")
		case "$s" in FULFILLING|SHIPPED|DELIVERED|CANCELLED) echo "$s"; return;; esac
		sleep 2
	done
	echo "${s:-UNKNOWN}"
}

# Bounded, because the first version of this spun for ever when a container failed to come back and
# the whole run hung with no output at all. A chaos harness that can hang is a chaos harness that
# will hang, since hanging things is precisely what it causes.
wait_healthy() {
	local svc="$1" limit="${2:-90}" i
	for ((i = 0; i < limit; i += 2)); do
		[ "$(docker compose ps "$svc" --format '{{.Health}}')" = "healthy" ] && return 0
		sleep 2
	done
	fail "$svc did not become healthy within ${limit}s"
	return 1
}

# --------------------------------------------------------------------------------------------------
# 1. Redis dies mid-sale.
#
# ADR 0016 claims the gate may only ever refuse, so losing Redis costs throughput and nothing else.
# This is that claim's only real test: if the gate were load-bearing, killing it would either oversell
# or take the service down, and both would show up here.
# --------------------------------------------------------------------------------------------------
redis_dies() {
	say "Redis dies mid-sale"
	local sku="CHAOS-REDIS-$(date +%s)"
	seed "$sku" 20

	step "warming the gate with one reservation"
	curl -sf --max-time 10 -X POST $G/api/v1/inventory/reservations -H 'Content-Type: application/json' \
		-d "{\"reservationKey\":\"warm-$sku\",\"customerId\":\"c\",\"ttlSeconds\":900,\"lines\":[{\"sku\":\"$sku\",\"quantity\":1}]}" > /dev/null

	step "killing redis"
	docker compose stop redis > /dev/null 2>&1

	step "40 buyers against 19 remaining units, with no gate"
	local granted=0 refused=0 other=0 code
	for i in $(seq 1 40); do
		code=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X POST $G/api/v1/inventory/reservations \
			-H 'Content-Type: application/json' \
			-d "{\"reservationKey\":\"nored-$sku-$i\",\"customerId\":\"c$i\",\"ttlSeconds\":900,\"lines\":[{\"sku\":\"$sku\",\"quantity\":1}]}")
		case "$code" in 201) granted=$((granted+1));; 409) refused=$((refused+1));; *) other=$((other+1));; esac
	done
	echo "     granted=$granted refused=$refused other=$other"

	local reserved; reserved=$(psql_i "select reserved from stock_items where sku='$sku'")
	if [ "$reserved" = "20" ] && [ "$granted" = "19" ]; then
		pass "sold exactly 20 of 20 with Redis down (19 after the warm-up hold)"
	else
		fail "expected 19 granted and reserved=20, got granted=$granted reserved=$reserved"
	fi
	[ "$other" = "0" ] && pass "no request errored with the gate unreachable" \
		|| fail "$other request(s) failed rather than being answered"

	step "restarting redis"
	docker compose start redis > /dev/null 2>&1
	wait_healthy redis
}

# --------------------------------------------------------------------------------------------------
# 2. Kafka dies with messages already committed to the outbox.
#
# This is the failure the outbox exists for. The order commits, the message is queued, and the broker
# is gone -- so nothing can be published. ADR 0017's claim is that nothing is lost and the relay
# drains once the broker returns. If the outbox were bypassed anywhere, the order would settle while
# Kafka was down and the message would be gone.
# --------------------------------------------------------------------------------------------------
kafka_dies() {
	say "Kafka dies with messages in the outbox"
	local sku="CHAOS-KAFKA-$(date +%s)"
	seed "$sku" 10

	step "killing kafka"
	docker compose stop kafka > /dev/null 2>&1

	step "placing an order with no broker to publish to"
	local order; order=$(place "$sku" 1)
	echo "     placed $order"
	sleep 8

	local queued; queued=$(psql_o "select count(*) from outbox_messages where published_at is null")
	local st; st=$(status_of "$order")
	if [ "${queued:-0}" -ge 1 ]; then
		pass "the message is safe in the outbox ($queued unpublished), order sits at $st"
	else
		fail "nothing queued with the broker down -- something published outside the outbox"
	fi

	step "bringing kafka back"
	docker compose start kafka > /dev/null 2>&1
	wait_healthy kafka

	step "waiting for the relay to drain and the saga to finish"
	local final; final=$(settle "$order" 120)
	local left; left=$(psql_o "select count(*) from outbox_messages where published_at is null")
	if [ "$final" = "FULFILLING" ] || [ "$final" = "SHIPPED" ]; then
		pass "the saga completed after recovery ($final) -- nothing was lost"
	else
		fail "order stuck at $final after the broker returned"
	fi
	[ "${left:-1}" = "0" ] && pass "outbox drained to zero" || fail "$left message(s) still unpublished"
}

# --------------------------------------------------------------------------------------------------
# 3. Inventory dies mid-saga, after the order is placed but before it can reserve.
#
# The command sits on the topic. Kafka's whole promise is that a consumer coming back reads it, and
# processed_events decides whether it has already been applied. Nothing here should need a human.
# --------------------------------------------------------------------------------------------------
inventory_dies() {
	say "Inventory dies mid-saga"
	local sku="CHAOS-INV-$(date +%s)"
	seed "$sku" 10

	step "killing inventory"
	docker compose stop inventory > /dev/null 2>&1

	step "placing an order nobody can reserve for"
	local order; order=$(place "$sku" 1)
	echo "     placed $order, status $(status_of "$order")"
	sleep 5

	step "restarting inventory"
	docker compose start inventory > /dev/null 2>&1
	wait_healthy inventory

	local final; final=$(settle "$order" 120)
	if [ "$final" = "FULFILLING" ] || [ "$final" = "SHIPPED" ]; then
		pass "the order completed once inventory returned ($final)"
	else
		fail "order stuck at $final after inventory came back"
	fi

	# Asserted against on_hand and the reservation status, not against `reserved`.
	#
	# The first version of this checked reserved=1, which was simply wrong: the order runs all the way
	# to SHIPPED, so the hold is COMMITTED rather than still held, and committing moves the unit out
	# of on_hand and returns reserved to zero. The scenario failed while reporting a platform that had
	# behaved perfectly.
	#
	# on_hand dropping by exactly one, with exactly one COMMITTED reservation, is the stronger claim
	# anyway: the command was applied once and only once, despite being redelivered to a consumer that
	# had been dead when it was first sent.
	local onhand; onhand=$(psql_i "select on_hand from stock_items where sku='$sku'")
	local committed; committed=$(psql_i "select count(*) from reservations r join reservation_lines l on l.reservation_id = r.id where l.sku = '$sku' and r.status = 'COMMITTED'")
	if [ "$onhand" = "9" ] && [ "$committed" = "1" ]; then
		pass "exactly one unit sold, exactly once (on_hand 10 -> 9, one COMMITTED hold)"
	else
		fail "expected on_hand=9 and one COMMITTED reservation, got on_hand=$onhand committed=$committed"
	fi
}

# --------------------------------------------------------------------------------------------------
# 4. Catalog dies, which is the one synchronous hop left in a checkout.
#
# ADR 0010 insists a refusal and a silence are different failures. Catalog being unreachable is a
# silence, and the order service must not invent a price, must not cancel an order it cannot price,
# and must not hang: it should refuse the checkout cleanly and leave nothing behind.
# --------------------------------------------------------------------------------------------------
catalog_dies() {
	say "Catalog dies during checkout"
	local sku="CHAOS-CAT-$(date +%s)"
	seed "$sku" 10

	step "killing catalog"
	docker compose stop catalog > /dev/null 2>&1

	local before; before=$(psql_o "select count(*) from orders")
	local body code
	body=$(curl -s --max-time 15 -o /tmp/chaos-cat.json -w '%{http_code}' -X POST $G/api/v1/orders \
		-H 'Content-Type: application/json' \
		-d "{\"idempotencyKey\":\"chaos-cat-$(date +%s%N)\",\"customerId\":\"chaos\",\"lines\":[{\"sku\":\"$sku\",\"quantity\":1}]}")
	code="$body"
	echo "     checkout returned $code: $(head -c 120 /tmp/chaos-cat.json)"

	if [ "$code" = "503" ] || [ "$code" = "502" ] || [ "$code" = "504" ]; then
		pass "refused cleanly with $code rather than inventing a price or hanging"
	else
		fail "expected 502/503/504 with catalog down, got $code"
	fi

	local after; after=$(psql_o "select count(*) from orders")
	[ "$before" = "$after" ] && pass "no half-built order persisted" \
		|| fail "order count moved $before -> $after with catalog unreachable"

	local held; held=$(psql_i "select coalesce(reserved,0) from stock_items where sku='$sku'")
	[ "${held:-0}" = "0" ] && pass "no stock stranded by a checkout that could not price itself" \
		|| fail "$held unit(s) reserved for an order that was never created"

	step "restarting catalog"
	docker compose start catalog > /dev/null 2>&1
	wait_healthy catalog
}

case "$SCENARIO" in
	redis)     redis_dies ;;
	kafka)     kafka_dies ;;
	inventory) inventory_dies ;;
	catalog)   catalog_dies ;;
	all)       redis_dies; kafka_dies; inventory_dies; catalog_dies ;;
	*) echo "unknown scenario: $SCENARIO (redis|kafka|inventory|catalog|all)"; exit 2 ;;
esac

echo ""
echo "=============================================================="
if [ "$FAILURES" -eq 0 ]; then
	echo " all assertions held"
else
	echo " $FAILURES assertion(s) FAILED"
fi
echo "=============================================================="
exit "$FAILURES"
