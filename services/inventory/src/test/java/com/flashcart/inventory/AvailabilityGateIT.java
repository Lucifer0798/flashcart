package com.flashcart.inventory;

import java.util.Map;
import java.util.UUID;

import com.flashcart.inventory.service.AvailabilityGate;
import com.flashcart.inventory.service.MeteredAvailabilityGate;
import com.flashcart.inventory.service.RedisAvailabilityGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate's own properties, against a real Redis.
 *
 * <p>{@code InventoryConcurrencyIT} already proves the important thing — that the gate cannot break
 * the no-oversell guarantee — because the whole suite now runs with it switched on. What is left is
 * the behaviour that only shows up when you look directly at the counter: that it warms, that it
 * refuses cheaply, that it hands tokens back, and above all that it fails open.
 */
class AvailabilityGateIT extends AbstractInventoryIT {

	private static final String KEY_PREFIX = "flashcart:avail:";

	@Autowired
	private AvailabilityGate gate;

	@Autowired
	private StringRedisTemplate redis;

	private String counter(String sku) {
		return redis.opsForValue().get(KEY_PREFIX + sku);
	}

	@Test
	@DisplayName("the gate is actually a Redis gate in this suite, not the no-op")
	void gateIsWiredIn() {
		// Guards against the whole suite silently proving nothing: if this were the disabled gate,
		// every test below would pass while exercising none of the code they are about.
		//
		// Unwrapped because Phase 9 wraps every gate in a metrics decorator, including the disabled
		// one -- which is deliberate, so that a run with the gate off is measured identically. That
		// makes the wrapper's own type useless as evidence, and the delegate's type the real answer.
		assertThat(gate).isInstanceOf(MeteredAvailabilityGate.class);
		assertThat(((MeteredAvailabilityGate) gate).delegate())
				.isInstanceOf(RedisAvailabilityGate.class);
	}

	@Test
	@DisplayName("the first reserve warms the counter from the database")
	void firstReserveWarmsTheCounter() {
		String sku = uniqueSku("WARM");
		createStock(sku, 10);
		assertThat(counter(sku)).as("counter is cold before anyone asks").isNull();

		reserve(uniqueKey("order"), "cust-1", sku, 3);

		// Seeded from what the database knew after the reserve: 10 on hand, 3 held, 7 available.
		assertThat(counter(sku)).isEqualTo("7");
	}

	@Test
	@DisplayName("a warmed counter is decremented by later reserves")
	void counterTracksSubsequentReserves() {
		String sku = uniqueSku("TRACK");
		createStock(sku, 10);
		reserve(uniqueKey("order"), "cust-1", sku, 1);
		assertThat(counter(sku)).isEqualTo("9");

		reserve(uniqueKey("order"), "cust-2", sku, 4);

		assertThat(counter(sku)).isEqualTo("5");
		assertStock(sku, 10, 5);
	}

	@Test
	@DisplayName("releasing gives the units back to the counter as well as to the database")
	void releaseReturnsUnitsToTheCounter() {
		String sku = uniqueSku("REL");
		createStock(sku, 5);
		var held = reserve(uniqueKey("order"), "cust-1", sku, 2).getBody();
		assertThat(counter(sku)).isEqualTo("3");

		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/release",
				Map.of("reason", "changed their mind"), Map.class);

		// Without this the counter would drift permanently downward and start refusing real stock.
		assertThat(counter(sku)).isEqualTo("5");
		assertStock(sku, 5, 0);
	}

	@Test
	@DisplayName("committing leaves the counter alone, because availability did not change")
	void commitDoesNotTouchTheCounter() {
		String sku = uniqueSku("COMMIT");
		createStock(sku, 5);
		var held = reserve(uniqueKey("order"), "cust-1", sku, 2).getBody();
		assertThat(counter(sku)).isEqualTo("3");

		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null,
				Map.class);

		// A commit drops on-hand and reserved together, so available is unchanged — and so is the
		// counter, which tracks availability rather than either number on its own.
		assertThat(counter(sku)).isEqualTo("3");
		assertStock(sku, 3, 0);
	}

	@Test
	@DisplayName("a warehouse change invalidates the estimate rather than trying to patch it")
	void receivingStockInvalidatesTheCounter() {
		String sku = uniqueSku("RECV");
		createStock(sku, 2);
		reserve(uniqueKey("order"), "cust-1", sku, 1);
		assertThat(counter(sku)).isEqualTo("1");

		rest.postForObject("/api/v1/inventory/stock/" + sku + "/receive",
				Map.of("quantity", 10, "reason", "pallet arrived"), Map.class);

		// Dropped, not incremented. The next reserve re-reads the database and seeds a value that is
		// certainly right, which is worth one query for something this rare.
		assertThat(counter(sku)).isNull();
	}

	@Test
	@DisplayName("the gate refuses without the database ever being asked")
	void gateRefusesBeforeTheDatabase() {
		String sku = uniqueSku("REFUSE");
		createStock(sku, 1);
		reserve(uniqueKey("order"), "cust-1", sku, 1);
		assertThat(counter(sku)).isEqualTo("0");

		ResponseEntity<Map> refused = reserveExpectingFailure(uniqueKey("order"), "cust-2", null, sku, 1);

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(refused.getBody()).containsEntry("code", "INSUFFICIENT_STOCK");
		// Still zero: a refusal must not decrement, or repeated sold-out requests would drive the
		// counter negative and it would never recover.
		assertThat(counter(sku)).isEqualTo("0");
	}

	@Test
	@DisplayName("a counter that has drifted low is corrected by the database, not trusted")
	void driftingLowOnlyCostsASale() {
		String sku = uniqueSku("DRIFT");
		createStock(sku, 10);
		// Force the estimate to lie: claim nothing is available when ten units are.
		redis.opsForValue().set(KEY_PREFIX + sku, "0");

		ResponseEntity<Map> refused = reserveExpectingFailure(uniqueKey("order"), "cust-1", null, sku, 1);
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// The important half: nothing was oversold, and the database is untouched and still correct.
		// This is the failure mode the design accepts — a lost sale, repaired within one TTL.
		assertStock(sku, 10, 0);

		redis.delete(KEY_PREFIX + sku);
		assertThat(reserve(uniqueKey("order"), "cust-1", sku, 1).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	@DisplayName("a counter that has drifted high costs a wasted query and nothing else")
	void driftingHighCannotOversell() {
		String sku = uniqueSku("OVER");
		createStock(sku, 2);
		// Claim far more is available than exists. The gate will admit; PostgreSQL must still refuse.
		redis.opsForValue().set(KEY_PREFIX + sku, "999");

		assertThat(reserve(uniqueKey("order"), "cust-1", sku, 2).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		ResponseEntity<Map> refused = reserveExpectingFailure(uniqueKey("order"), "cust-2", null, sku, 1);

		// The gate said yes and the database said no. That is the arrangement working, not failing:
		// the gate is only ever allowed to refuse.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertStock(sku, 2, 2);
		// And the token it admitted was handed straight back, so the estimate does not leak downward.
		assertThat(Integer.parseInt(counter(sku))).isGreaterThan(990);
	}

	@Test
	@DisplayName("with no counter at all the service behaves exactly as it did before Redis existed")
	void coldCounterFallsThroughToTheDatabase() {
		String sku = uniqueSku("COLD");
		createStock(sku, 3);
		redis.delete(KEY_PREFIX + sku);

		// UNKNOWN, so the database decides — which is the same code path a Redis outage takes.
		assertThat(reserve(uniqueKey("order"), "cust-1", sku, 3).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(reserveExpectingFailure(uniqueKey("order"), "cust-2", null, sku, 1).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
		assertStock(sku, 3, 3);
	}

	@Test
	@DisplayName("the gate never invents a counter for a SKU it has never seen")
	void releaseDoesNotCreateACounter() {
		String sku = uniqueSku("GHOST");

		gate.release(sku, 5);

		// INCRBY against a missing key would start from zero and claim exactly five units are
		// available — an estimate conjured from nothing, and one that could refuse real stock.
		assertThat(counter(sku)).isNull();
	}

	@Test
	@DisplayName("the counter is estimate-shaped: it survives a reserve for a flash sale too")
	void worksAlongsideSaleAllocations() {
		String sku = uniqueSku("SALE");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 100);
		allocate(saleId, sku, 2, 1);

		assertThat(reserve(uniqueKey("order"), "cust-1", saleId, sku, 1, null).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(reserve(uniqueKey("order"), "cust-2", saleId, sku, 1, null).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		// The allocation is exhausted, and that refusal comes from PostgreSQL — the gate knows
		// nothing about sale allocations and must not be relied on to enforce them.
		ResponseEntity<Map> refused = reserveExpectingFailure(uniqueKey("order"), "cust-3", saleId, sku, 1);
		assertThat(refused.getBody()).containsEntry("code", "SALE_ALLOCATION_EXHAUSTED");
		// The warehouse still has plenty, so the gate would happily have admitted it.
		assertThat(Integer.parseInt(counter(sku))).isGreaterThan(90);
	}
}
