package com.flashcart.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.inventory.api.dto.AdjustStockRequest;
import com.flashcart.inventory.api.dto.AllocationRequest;
import com.flashcart.inventory.api.dto.AllocationResponse;
import com.flashcart.inventory.api.dto.CreateStockRequest;
import com.flashcart.inventory.api.dto.ReceiveStockRequest;
import com.flashcart.inventory.api.dto.ReservationResponse;
import com.flashcart.inventory.api.dto.ReserveRequest;
import com.flashcart.inventory.api.dto.StockResponse;
import com.flashcart.inventory.domain.ReservationStatus;
import com.flashcart.inventory.service.ReservationExpiryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** The inventory lifecycle against a real PostgreSQL, driven over real HTTP. */
class InventoryIT extends AbstractInventoryIT {

	@Autowired
	private ReservationExpiryService expiryService;

	// --- the happy path --------------------------------------------------------------------------

	@Test
	@DisplayName("reserve holds units without removing them, commit removes them")
	void reserveThenCommit() {
		String sku = uniqueSku("AUD");
		createStock(sku, 100);

		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", sku, 3).getBody();

		assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
		assertThat(held.expiresAt()).isNotNull();
		// A hold takes units out of circulation but not out of the warehouse — they are still
		// physically there, which is why on-hand is unchanged and available is not.
		assertStock(sku, 100, 3);

		ReservationResponse committed = rest.postForObject(
				"/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null,
				ReservationResponse.class);

		assertThat(committed.status()).isEqualTo(ReservationStatus.COMMITTED);
		assertThat(committed.committedAt()).isNotNull();
		// Now they have left: both counters drop together.
		assertStock(sku, 97, 0);
	}

	@Test
	@DisplayName("releasing a hold puts the units straight back")
	void reserveThenRelease() {
		String sku = uniqueSku("AUD");
		createStock(sku, 10);
		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", sku, 4).getBody();
		assertStock(sku, 10, 4);

		ReservationResponse released = rest.postForObject(
				"/api/v1/inventory/reservations/" + held.reservationKey() + "/release",
				Map.of("reason", "payment declined"), ReservationResponse.class);

		assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
		assertStock(sku, 10, 0);
	}

	@Test
	@DisplayName("a multi-line reservation is all or nothing")
	void multiLineReservationIsAtomic() {
		String plentiful = uniqueSku("AUD");
		String scarce = uniqueSku("WEA");
		createStock(plentiful, 100);
		createStock(scarce, 1);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/inventory/reservations",
				new ReserveRequest(uniqueKey("order"), "cust-1", null, null, List.of(
						new ReserveRequest.Line(plentiful, 5),
						new ReserveRequest.Line(scarce, 10))),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_STOCK");
		// The line that *could* have been held must not have been: a basket that cannot be filled
		// takes nothing, or a customer ends up holding half an order they can never complete.
		assertStock(plentiful, 100, 0);
		assertStock(scarce, 1, 0);
	}

	// --- the point of the service ----------------------------------------------------------------

	@Test
	@DisplayName("reserving more than is available is refused")
	void cannotReserveMoreThanAvailable() {
		String sku = uniqueSku("AUD");
		createStock(sku, 5);

		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "cust-1", null, sku, 6);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "INSUFFICIENT_STOCK");
		assertStock(sku, 5, 0);
	}

	@Test
	@DisplayName("held units are not available to the next buyer")
	void heldUnitsAreNotAvailable() {
		String sku = uniqueSku("AUD");
		createStock(sku, 5);
		reserve(uniqueKey("order"), "cust-1", sku, 5);

		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "cust-2", null, sku, 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		// The warehouse still physically holds five, and that is exactly why "available" and not
		// "on hand" is the number a storefront may render.
		assertStock(sku, 5, 5);
	}

	@Test
	@DisplayName("a retried reserve returns the original hold instead of taking a second")
	void reserveIsIdempotent() {
		String sku = uniqueSku("AUD");
		createStock(sku, 100);
		String key = uniqueKey("order");

		ReservationResponse first = reserve(key, "cust-1", sku, 3).getBody();
		ReservationResponse retry = reserve(key, "cust-1", sku, 3).getBody();

		assertThat(retry.id()).isEqualTo(first.id());
		// Three units held, not six. At-least-once delivery makes this retry a certainty, and
		// without the idempotency key every network blip would quietly double-hold stock.
		assertStock(sku, 100, 3);
	}

	@Test
	@DisplayName("committing twice is a no-op, not a double deduction")
	void commitIsIdempotent() {
		String sku = uniqueSku("AUD");
		createStock(sku, 50);
		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", sku, 2).getBody();
		String commitUrl = "/api/v1/inventory/reservations/" + held.reservationKey() + "/commit";

		rest.postForObject(commitUrl, null, ReservationResponse.class);
		ReservationResponse second = rest.postForObject(commitUrl, null, ReservationResponse.class);

		assertThat(second.status()).isEqualTo(ReservationStatus.COMMITTED);
		assertStock(sku, 48, 0);
	}

	@Test
	@DisplayName("releasing twice returns the units once")
	void releaseIsIdempotent() {
		String sku = uniqueSku("AUD");
		createStock(sku, 10);
		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", sku, 4).getBody();
		String releaseUrl = "/api/v1/inventory/reservations/" + held.reservationKey() + "/release";

		rest.postForObject(releaseUrl, Map.of("reason", "first"), ReservationResponse.class);
		rest.postForObject(releaseUrl, Map.of("reason", "second"), ReservationResponse.class);

		assertStock(sku, 10, 0);
	}

	// --- expiry ----------------------------------------------------------------------------------

	@Test
	@DisplayName("an expired hold is reclaimed inline by the next buyer, without waiting for a sweeper")
	void expiredHoldIsReclaimedLazily() throws InterruptedException {
		String sku = uniqueSku("AUD");
		createStock(sku, 1);
		// One second, so the hold lapses inside the test rather than in fifteen minutes.
		ReservationResponse abandoned = reserve(uniqueKey("order"), "cust-1", null, sku, 1, 1).getBody();
		assertStock(sku, 1, 1);

		Thread.sleep(1_200);

		// The sweeper is disabled in this suite, so if this succeeds it is the lazy reclaim on the
		// reserve path that did it — which is the whole point: a buyer must never be told "sold out"
		// because a background job had not caught up yet.
		ResponseEntity<ReservationResponse> second = reserve(uniqueKey("order"), "cust-2", sku, 1);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertStock(sku, 1, 1);
		assertThat(rest.getForObject("/api/v1/inventory/reservations/" + abandoned.reservationKey(),
				ReservationResponse.class).status()).isEqualTo(ReservationStatus.EXPIRED);
	}

	@Test
	@DisplayName("the sweeper reclaims holds on a SKU nobody is asking about any more")
	void sweeperReclaimsColdSkus() throws InterruptedException {
		String sku = uniqueSku("COLD");
		createStock(sku, 10);
		ReservationResponse abandoned = reserve(uniqueKey("order"), "cust-1", null, sku, 6, 1).getBody();

		Thread.sleep(1_200);
		// Nothing will ever reserve this SKU again, so the lazy path will never fire. Without the
		// sweeper these six units would be lost for good — a slow stock leak, not a visible failure.
		int expired = expiryService.sweepBatch();

		assertThat(expired).isGreaterThanOrEqualTo(1);
		assertStock(sku, 10, 0);
		assertThat(rest.getForObject("/api/v1/inventory/reservations/" + abandoned.reservationKey(),
				ReservationResponse.class).status()).isEqualTo(ReservationStatus.EXPIRED);
	}

	@Test
	@DisplayName("committing an expired hold is refused, because the units may already be someone else's")
	void expiredHoldCannotBeCommitted() throws InterruptedException {
		String sku = uniqueSku("AUD");
		createStock(sku, 5);
		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", null, sku, 2, 1).getBody();

		Thread.sleep(1_200);
		expiryService.sweepBatch();

		ResponseEntity<Map> response = rest.postForEntity(
				"/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null, Map.class);

		// A 409 rather than a silent success: the order service must reconcile, not confirm an order
		// it may not be able to fill.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "RESERVATION_NOT_HELD");
		assertStock(sku, 5, 0);
	}

	@Test
	@DisplayName("a caller cannot park stock for longer than the server allows")
	void ttlIsCapped() {
		String sku = uniqueSku("AUD");
		createStock(sku, 5);

		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", null, sku, 1, 86_400).getBody();

		// Capped to the configured maximum of one hour rather than rejected — friendlier than a 400,
		// and still stops any client holding scarce stock indefinitely.
		assertThat(held.expiresAt()).isBefore(held.createdAt().plusSeconds(3_601));
	}

	// --- flash-sale allocations -------------------------------------------------------------------

	@Test
	@DisplayName("a sale can only sell its allocation, however full the warehouse is")
	void saleCannotExceedItsAllocation() {
		String sku = uniqueSku("AUD");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 1_000);
		allocate(saleId, sku, 3, 5);

		reserve(uniqueKey("order"), "cust-1", saleId, sku, 3, null);
		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "cust-2", saleId, sku, 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "SALE_ALLOCATION_EXHAUSTED");
		// 997 units sit in the warehouse and the sale still cannot touch them. That is the point of
		// keeping the allocation separate from the stock position.
		assertStock(sku, 1_000, 3);
	}

	@Test
	@DisplayName("a sale cannot sell a SKU it was never allocated")
	void saleNeedsAnAllocation() {
		String sku = uniqueSku("AUD");
		createStock(sku, 100);

		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "cust-1",
				UUID.randomUUID(), sku, 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "NO_SALE_ALLOCATION");
	}

	@Test
	@DisplayName("committing moves units from held to sold without changing what the sale has consumed")
	void commitMovesAllocationFromHeldToSold() {
		String sku = uniqueSku("AUD");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 100);
		allocate(saleId, sku, 10, 5);
		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", saleId, sku, 4, null).getBody();

		AllocationResponse duringHold = rest.getForObject(
				"/api/v1/inventory/allocations/" + saleId + "/" + sku, AllocationResponse.class);
		assertThat(duringHold.reservedUnits()).isEqualTo(4);
		assertThat(duringHold.committedUnits()).isZero();
		assertThat(duringHold.remainingUnits()).isEqualTo(6);

		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null,
				ReservationResponse.class);

		AllocationResponse afterCommit = rest.getForObject(
				"/api/v1/inventory/allocations/" + saleId + "/" + sku, AllocationResponse.class);
		assertThat(afterCommit.reservedUnits()).isZero();
		assertThat(afterCommit.committedUnits()).isEqualTo(4);
		// Unchanged: a commit settles units, it does not consume more of the allocation.
		assertThat(afterCommit.remainingUnits()).isEqualTo(6);
	}

	@Test
	@DisplayName("an allocation cannot be shrunk below what the sale has already taken")
	void allocationCannotShrinkBelowConsumed() {
		String sku = uniqueSku("AUD");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 100);
		// Per-customer limit of 10, not 5: this test is about the allocation, and a cap below the
		// quantity reserved below would refuse the reservation for an entirely unrelated reason.
		allocate(saleId, sku, 10, 10);
		reserve(uniqueKey("order"), "cust-1", saleId, sku, 6, null);

		ResponseEntity<Map> response = rest.exchange("/api/v1/inventory/allocations/" + saleId + "/" + sku,
				HttpMethod.PUT, new HttpEntity<>(Map.of("allocatedUnits", 3)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "ALLOCATION_BELOW_CONSUMED");
	}

	// --- per-customer caps -------------------------------------------------------------------------

	@Test
	@DisplayName("a customer cannot exceed the per-customer cap across separate orders")
	void perCustomerCapSpansOrders() {
		String sku = uniqueSku("AUD");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 100);
		allocate(saleId, sku, 50, 2);

		reserve(uniqueKey("order"), "scalper", saleId, sku, 2, null);
		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "scalper", saleId, sku, 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "CUSTOMER_LIMIT_EXCEEDED");
		// Another customer is unaffected — the cap is per customer, not a second global limit.
		assertThat(reserve(uniqueKey("order"), "someone-else", saleId, sku, 2, null).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	@DisplayName("a single request over the cap is refused outright")
	void singleRequestOverCapIsRefused() {
		String sku = uniqueSku("AUD");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 100);
		allocate(saleId, sku, 50, 1);

		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "cust-1", saleId, sku, 5);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "CUSTOMER_LIMIT_EXCEEDED");
	}

	@Test
	@DisplayName("an abandoned hold gives the customer their allowance back; a purchase does not")
	void capIsRestoredByReleaseButNotByCommit() {
		String sku = uniqueSku("AUD");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 100);
		allocate(saleId, sku, 50, 1);

		ReservationResponse abandoned = reserve(uniqueKey("order"), "cust-1", saleId, sku, 1, null).getBody();
		rest.postForObject("/api/v1/inventory/reservations/" + abandoned.reservationKey() + "/release",
				Map.of("reason", "changed their mind"), ReservationResponse.class);

		// Their hold lapsed rather than became a purchase, so they get to try again.
		ReservationResponse retry = reserve(uniqueKey("order"), "cust-1", saleId, sku, 1, null).getBody();
		assertThat(retry.status()).isEqualTo(ReservationStatus.HELD);

		rest.postForObject("/api/v1/inventory/reservations/" + retry.reservationKey() + "/commit", null,
				ReservationResponse.class);

		// Now they have actually bought one, and the cap holds — otherwise "one per customer" would
		// only ever have meant "one at a time".
		ResponseEntity<Map> third = reserveExpectingFailure(uniqueKey("order"), "cust-1", saleId, sku, 1);
		assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(third.getBody()).containsEntry("code", "CUSTOMER_LIMIT_EXCEEDED");
	}

	// --- warehouse operations ----------------------------------------------------------------------

	@Test
	@DisplayName("receiving stock increases what is available")
	void receiveIncreasesStock() {
		String sku = uniqueSku("AUD");
		createStock(sku, 10);

		StockResponse after = rest.postForObject("/api/v1/inventory/stock/" + sku + "/receive",
				new ReceiveStockRequest(15, "pallet arrived"), StockResponse.class);

		assertThat(after.onHand()).isEqualTo(25);
		assertThat(after.available()).isEqualTo(25);
	}

	@Test
	@DisplayName("a negative adjustment cannot eat into units that are already promised")
	void adjustmentCannotEatHeldUnits() {
		String sku = uniqueSku("AUD");
		createStock(sku, 10);
		reserve(uniqueKey("order"), "cust-1", sku, 8);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/inventory/stock/" + sku + "/adjust",
				new AdjustStockRequest(-5, "water damage"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "ADJUSTMENT_BELOW_RESERVED");
		assertStock(sku, 10, 8);
	}

	@Test
	@DisplayName("an adjustment must say why")
	void adjustmentRequiresAReason() {
		String sku = uniqueSku("AUD");
		createStock(sku, 10);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/inventory/stock/" + sku + "/adjust",
				Map.of("delta", -1), Map.class);

		// Without a reason the ledger cannot answer the one question it exists for.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("tracking the same SKU twice is a 409, not a second row")
	void duplicateSkuIsAConflict() {
		String sku = uniqueSku("AUD");
		createStock(sku, 1);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/inventory/stock",
				new CreateStockRequest(sku, 5, null), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "SKU_ALREADY_TRACKED");
	}

	@Test
	@DisplayName("reserving an untracked SKU is a 404, not a silent success")
	void untrackedSkuIsNotFound() {
		ResponseEntity<Map> response = reserveExpectingFailure(uniqueKey("order"), "cust-1", null,
				uniqueSku("GHOST"), 1);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// --- the ledger --------------------------------------------------------------------------------

	@Test
	@DisplayName("every movement of a unit leaves a ledger entry that replays to the balance")
	void ledgerExplainsThePosition() {
		String sku = uniqueSku("AUD");
		createStock(sku, 20);
		ReservationResponse held = reserve(uniqueKey("order"), "cust-1", sku, 5).getBody();
		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null,
				ReservationResponse.class);

		ResponseEntity<Map> response = rest.getForEntity(
				"/api/v1/inventory/stock/" + sku + "/movements", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> movements = (List<Map<String, Object>>) response.getBody().get("content");
		assertThat(movements).extracting(m -> m.get("type"))
				.containsExactlyInAnyOrder("RECEIVED", "RESERVED", "COMMITTED");

		// The deltas replay to the current position: 20 received, then 5 committed away.
		int onHandDelta = movements.stream().mapToInt(m -> (int) m.get("onHandDelta")).sum();
		int reservedDelta = movements.stream().mapToInt(m -> (int) m.get("reservedDelta")).sum();
		assertThat(onHandDelta).isEqualTo(15);
		assertThat(reservedDelta).isZero();
		assertStock(sku, 15, 0);

		// And each entry names the request that caused it, so the ledger leads back to a log line.
		assertThat(movements).allSatisfy(m -> assertThat(m.get("correlationId")).isNotNull());
	}

	@Test
	@DisplayName("an expired hold is distinguishable from a released one in the ledger")
	void ledgerDistinguishesExpiryFromRelease() throws InterruptedException {
		String sku = uniqueSku("AUD");
		createStock(sku, 10);
		reserve(uniqueKey("order"), "cust-1", null, sku, 1, 1);

		Thread.sleep(1_200);
		expiryService.sweepBatch();

		ResponseEntity<Map> response = rest.getForEntity(
				"/api/v1/inventory/stock/" + sku + "/movements", Map.class);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> movements = (List<Map<String, Object>>) response.getBody().get("content");

		// Who gave up versus who ran out of time is a different operational question, so the ledger
		// keeps them apart rather than collapsing both into "released".
		assertThat(movements).extracting(m -> m.get("type")).contains("EXPIRED").doesNotContain("RELEASED");
	}

	// --- cross-cutting -----------------------------------------------------------------------------

	@Test
	@DisplayName("the service reports itself live, and says how it is configured")
	void serviceInfoReportsConfiguration() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/inventory/_info", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "live");
		assertThat(response.getBody()).containsEntry("reservationStrategy", "ATOMIC_UPDATE");
	}
}
