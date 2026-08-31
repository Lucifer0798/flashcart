package com.flashcart.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.flashcart.inventory.api.dto.AllocationResponse;
import com.flashcart.inventory.api.dto.StockResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test the whole phase exists to pass.
 *
 * <p>Every other test here checks a rule in isolation, one request at a time. None of them would
 * catch the bug this service is built to prevent, because that bug only appears when many requests
 * arrive at once: read the stock, see units available, and by the time you write, so did fifty
 * others. A single-threaded suite is green on that code.
 *
 * <p>So these tests do the only thing that actually demonstrates it — fire many genuinely concurrent
 * requests at scarce stock and count. Every thread is parked on a latch and released together, so
 * the requests genuinely overlap rather than politely queueing.
 *
 * <p>The assertion in all three cases is the same and is exact, not approximate: <em>exactly</em> the
 * available number of requests succeed. Not "roughly". Not "no more than". A system that lets one
 * extra through has oversold, and a system that lets one fewer through has lost a sale.
 */
class InventoryConcurrencyIT extends AbstractInventoryIT {

	/** Enough overlap to make a read-then-write race certain, small enough to stay quick. */
	private static final int CONCURRENT_BUYERS = 60;

	/** What every buyer got back, tallied. */
	private record Outcome(int created, int conflict, int other, List<String> errorCodes) {
	}

	/**
	 * Runs {@code attempts} requests that all start at the same moment.
	 *
	 * <p>The latch is what makes this a concurrency test rather than a slow sequential one: without
	 * it, thread N is often finished before thread N+1 has been scheduled, and nothing ever contends.
	 */
	private Outcome stampede(int attempts, Callable<ResponseEntity<Map>> request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		CountDownLatch startGun = new CountDownLatch(1);
		AtomicInteger created = new AtomicInteger();
		AtomicInteger conflict = new AtomicInteger();
		AtomicInteger other = new AtomicInteger();
		List<String> errorCodes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		try {
			List<Future<?>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				futures.add(pool.submit(() -> {
					startGun.await();
					ResponseEntity<Map> response = request.call();
					HttpStatusCode status = response.getStatusCode();
					if (status == HttpStatus.CREATED) {
						created.incrementAndGet();
					}
					else if (status == HttpStatus.CONFLICT) {
						conflict.incrementAndGet();
						errorCodes.add(String.valueOf(response.getBody().get("code")));
					}
					else {
						other.incrementAndGet();
						errorCodes.add(status + ":" + response.getBody());
					}
					return null;
				}));
			}

			startGun.countDown();
			for (Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}
		return new Outcome(created.get(), conflict.get(), other.get(), errorCodes);
	}

	@Test
	@DisplayName("60 buyers, 20 units: exactly 20 succeed and nothing is oversold")
	void concurrentBuyersCannotOversell() throws Exception {
		String sku = uniqueSku("RUSH");
		int units = 20;
		createStock(sku, units);

		Outcome outcome = stampede(CONCURRENT_BUYERS, () -> reserveExpectingFailure(
				uniqueKey("order"), "cust-" + UUID.randomUUID(), null, sku, 1));

		assertThat(outcome.other())
				.as("no request should fail for any reason other than losing the race: %s", outcome.errorCodes())
				.isZero();
		// Exact, both ways. One too many is an oversell; one too few is a sale left on the table
		// because the service was needlessly pessimistic.
		assertThat(outcome.created()).as("successful reservations").isEqualTo(units);
		assertThat(outcome.conflict()).as("buyers who lost the race").isEqualTo(CONCURRENT_BUYERS - units);
		assertThat(outcome.errorCodes()).allMatch("INSUFFICIENT_STOCK"::equals);

		StockResponse position = stock(sku);
		assertThat(position.reserved()).isEqualTo(units);
		assertThat(position.available()).isZero();
		// The database-level guarantee, restated: reserved can never exceed on-hand. If the
		// application logic were wrong, the CHECK constraint would have thrown instead.
		assertThat(position.reserved()).isLessThanOrEqualTo(position.onHand());
	}

	@Test
	@DisplayName("a flash sale's allocation holds under the same stampede, with the warehouse still full")
	void concurrentBuyersCannotExceedTheAllocation() throws Exception {
		String sku = uniqueSku("DROP");
		UUID saleId = UUID.randomUUID();
		int allocated = 15;
		// Ten times more stock than the sale may sell, so the only thing that can stop a buyer is
		// the allocation — which is precisely what is under test.
		createStock(sku, allocated * 10);
		allocate(saleId, sku, allocated, 1);

		Outcome outcome = stampede(CONCURRENT_BUYERS, () -> reserveExpectingFailure(
				uniqueKey("order"), "cust-" + UUID.randomUUID(), saleId, sku, 1));

		assertThat(outcome.other()).as("unexpected failures: %s", outcome.errorCodes()).isZero();
		assertThat(outcome.created()).isEqualTo(allocated);
		assertThat(outcome.errorCodes()).allMatch("SALE_ALLOCATION_EXHAUSTED"::equals);

		AllocationResponse allocation = rest.getForObject(
				"/api/v1/inventory/allocations/" + saleId + "/" + sku, AllocationResponse.class);
		assertThat(allocation.reservedUnits()).isEqualTo(allocated);
		assertThat(allocation.remainingUnits()).isZero();
		// The warehouse still holds plenty. The sale simply may not have it.
		assertThat(stock(sku).available()).isEqualTo(allocated * 10 - allocated);
	}

	@Test
	@DisplayName("a scalper firing 60 requests at once still gets exactly their cap")
	void concurrentRequestsCannotBeatThePerCustomerCap() throws Exception {
		String sku = uniqueSku("SCALP");
		UUID saleId = UUID.randomUUID();
		int perCustomerLimit = 2;
		createStock(sku, 500);
		allocate(saleId, sku, 500, perCustomerLimit);

		// The attack the cap exists to stop: one customer, many simultaneous requests, every one of
		// which would read "zero consumed so far" if the check were a read followed by a write.
		Outcome outcome = stampede(CONCURRENT_BUYERS, () -> reserveExpectingFailure(
				uniqueKey("order"), "one-determined-scalper", saleId, sku, 1));

		assertThat(outcome.other()).as("unexpected failures: %s", outcome.errorCodes()).isZero();
		assertThat(outcome.created()).isEqualTo(perCustomerLimit);
		assertThat(outcome.errorCodes()).allMatch("CUSTOMER_LIMIT_EXCEEDED"::equals);
		assertThat(stock(sku).reserved()).isEqualTo(perCustomerLimit);
	}

	@Test
	@DisplayName("concurrent retries of one order key produce one hold, not sixty")
	void concurrentRetriesOfTheSameKeyHoldOnce() throws Exception {
		String sku = uniqueSku("RETRY");
		createStock(sku, 100);
		String sharedKey = uniqueKey("order");

		// The same request arriving many times at once — a client retry storm, or several instances
		// of a caller reacting to the same at-least-once event.
		Outcome outcome = stampede(CONCURRENT_BUYERS, () -> reserveExpectingFailure(
				sharedKey, "cust-1", null, sku, 1));

		assertThat(outcome.other()).as("unexpected failures: %s", outcome.errorCodes()).isZero();
		// Every caller gets a successful answer; only one hold exists behind them all.
		assertThat(outcome.created() + outcome.conflict()).isEqualTo(CONCURRENT_BUYERS);
		assertThat(stock(sku).reserved())
				.as("one unit held however many times the request arrived")
				.isEqualTo(1);
	}
}
