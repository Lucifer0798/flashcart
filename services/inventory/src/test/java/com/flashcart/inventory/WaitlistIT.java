package com.flashcart.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.flashcart.common.event.message.BackInStock;
import com.flashcart.inventory.api.dto.ReceiveStockRequest;
import com.flashcart.inventory.api.dto.AllocationResponse;
import com.flashcart.inventory.api.dto.ReservationResponse;
import com.flashcart.inventory.api.dto.StockResponse;
import com.flashcart.inventory.domain.ReservationStatus;
import com.flashcart.inventory.service.ReservationExpiryService;
import com.flashcart.inventory.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue for sold-out SKUs. See ADR 0044.
 *
 * <p>Every way units come back is exercised here -- received, released, expired, returned -- because
 * the notice is triggered from the ledger rather than from each of them, and a path that skipped the
 * ledger would tell nobody without any one test of that path noticing.
 */
class WaitlistIT extends AbstractInventoryIT {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private ReservationExpiryService expiryService;

	// --- joining --------------------------------------------------------------------------------

	@Test
	@DisplayName("joining a sold-out SKU gives a place, and joining again gives the same one")
	void joinIsAPlaceInAQueue() {
		String sku = soldOut();

		ResponseEntity<Map> first = join("shopper-a", sku);
		ResponseEntity<Map> second = join("shopper-b", sku);
		ResponseEntity<Map> again = join("shopper-a", sku);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(first.getBody()).containsEntry("status", "WAITING").containsEntry("ahead", 0);
		assertThat(second.getBody()).containsEntry("ahead", 1);
		// Idempotent: the same place, not a second one at the back.
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
	}

	@Test
	@DisplayName("a SKU with units to buy cannot be queued for; buying is the answer")
	void inStockIsRefused() {
		String sku = uniqueSku("WAIT");
		createStock(sku, 5);

		ResponseEntity<Map> response = join("shopper-a", sku);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "IN_STOCK");
	}

	@Test
	@DisplayName("an unknown SKU is a 404, and joining needs a real token")
	void unknownSkuAndNoToken() {
		assertThat(join("shopper-a", uniqueSku("NOPE")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpHeaders rubbish = new HttpHeaders();
		rubbish.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-token");
		ResponseEntity<Map> anonymous = rest.exchange("/api/v1/inventory/waitlist", HttpMethod.POST,
				new HttpEntity<>(Map.of("sku", soldOut()), rubbish), Map.class);
		assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a shopper reaches the waitlist and nothing else in this service")
	void shopperReachesOnlyTheWaitlist() {
		String sku = soldOut();
		assertThat(join("shopper-a", sku).getStatusCode()).isEqualTo(HttpStatus.CREATED);

		// The signed-in rule opened three paths. A shopper token must still be refused everywhere else,
		// or the rule was written wider than it reads.
		ResponseEntity<Map> receive = rest.exchange("/api/v1/inventory/stock/" + sku + "/receive",
				HttpMethod.POST, new HttpEntity<>(new ReceiveStockRequest(5, "not yours"), as("shopper-a")),
				Map.class);
		assertThat(receive.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// --- who is told -------------------------------------------------------------------------------

	@Test
	@DisplayName("two units back, three waiting: the oldest two are told and the third keeps its place")
	void oldestFirstAndOnlyAsManyAsUnits() {
		String sku = soldOut();
		join("shopper-a", sku);
		join("shopper-b", sku);
		join("shopper-c", sku);

		receive(sku, 2);

		assertThat(statusOf("shopper-a", sku)).isEqualTo("NOTIFIED");
		assertThat(statusOf("shopper-b", sku)).isEqualTo("NOTIFIED");
		assertThat(statusOf("shopper-c", sku)).isEqualTo("WAITING");
		assertThat(entryOf("shopper-c", sku)).containsEntry("ahead", 0);
		assertThat(noticesFor(sku)).containsExactlyInAnyOrder("shopper-a", "shopper-b");

		receive(sku, 1);

		assertThat(statusOf("shopper-c", sku)).isEqualTo("NOTIFIED");
		// Still one notice each. Being told is final, so later units go to whoever is next, not again
		// to whoever was first.
		assertThat(noticesFor(sku)).containsExactlyInAnyOrder("shopper-a", "shopper-b", "shopper-c");
	}

	@Test
	@DisplayName("a released hold puts units back, and the queue hears about it")
	void releaseNotifies() {
		String sku = uniqueSku("WAIT");
		createStock(sku, 1);
		ReservationResponse held = reserve(uniqueKey("order"), "buyer", sku, 1).getBody();
		join("shopper-a", sku);

		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/release",
				Map.of("reason", "payment declined"), ReservationResponse.class);

		assertThat(statusOf("shopper-a", sku)).isEqualTo("NOTIFIED");
	}

	@Test
	@DisplayName("an expired hold puts units back, and the queue hears about it")
	void expiryNotifies() throws InterruptedException {
		String sku = uniqueSku("WAIT");
		createStock(sku, 1);
		reserve(uniqueKey("order"), "buyer", null, sku, 1, 1);
		join("shopper-a", sku);

		Thread.sleep(1_200);
		expiryService.sweepBatch();

		assertThat(statusOf("shopper-a", sku)).isEqualTo("NOTIFIED");
	}

	@Test
	@DisplayName("a cancelled sale returns its units, and the queue hears about it")
	void returnNotifies() {
		String sku = uniqueSku("WAIT");
		createStock(sku, 1);
		ReservationResponse held = reserve(uniqueKey("order"), "buyer", sku, 1).getBody();
		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null,
				ReservationResponse.class);
		join("shopper-a", sku);

		reservationService.returnToStock(held.reservationKey(), "cancelled after payment");

		assertThat(statusOf("shopper-a", sku)).isEqualTo("NOTIFIED");
	}

	@Test
	@DisplayName("a hold becoming a sale frees nothing, and tells nobody")
	void commitTellsNobody() {
		String sku = uniqueSku("WAIT");
		createStock(sku, 1);
		ReservationResponse held = reserve(uniqueKey("order"), "buyer", sku, 1).getBody();
		join("shopper-a", sku);

		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/commit", null,
				ReservationResponse.class);

		// On-hand and reserved both fall by one: available is unchanged at zero.
		assertThat(statusOf("shopper-a", sku)).isEqualTo("WAITING");
		assertThat(noticesFor(sku)).isEmpty();
	}

	@Test
	@DisplayName("concurrent arrivals tell different shoppers, never the same one twice")
	void concurrentArrivalsNeverDoubleNotify() throws Exception {
		String sku = soldOut();
		for (int i = 0; i < 10; i++) {
			join("queue-" + i, sku);
		}

		// Five receives at once. Some may lose the stock row's optimistic lock and be refused with a 409;
		// what matters is that every unit that did arrive told exactly one distinct shopper.
		ExecutorService pool = Executors.newFixedThreadPool(5);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpStatus>> results = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return (HttpStatus) rest.postForEntity("/api/v1/inventory/stock/" + sku + "/receive",
						new ReceiveStockRequest(1, "concurrent"), Map.class).getStatusCode();
			}));
		}
		start.countDown();
		int received = 0;
		for (Future<HttpStatus> result : results) {
			if (result.get(30, TimeUnit.SECONDS) == HttpStatus.OK) {
				received++;
			}
		}
		pool.shutdown();

		List<String> told = noticesFor(sku);
		assertThat(received).isPositive();
		assertThat(told).hasSize(received);
		assertThat(new HashSet<>(told)).hasSize(told.size());
		assertThat(stock(sku).onHand()).isEqualTo(received);
	}

	@Test
	@DisplayName("a shopper leaving while units arrive is skipped, not told, and the next in line is told")
	void leaveRacingANoticeSkipsTheLeaver() throws Exception {
		String sku = soldOut();
		String a = (String) join("shopper-a", sku).getBody().get("id");
		join("shopper-b", sku);

		// Shopper A is mid-leave: their row is locked by a transaction that has not committed. Concurrent
		// arrivals of the same SKU are already serialised by the stock row; this race is the one the
		// claim's own lock exists for.
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		Future<?> leaving = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
			jdbc.queryForObject("select id from waitlist_entries where id = ?::uuid for update", String.class, a);
			locked.countDown();
			try {
				finish.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			jdbc.update("update waitlist_entries set status = 'CANCELLED', cancelled_at = now() where id = ?::uuid", a);
		}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

		// One unit arrives while A's leave is in flight. It must not wait for A, and must not go to A.
		Future<?> arriving = pool.submit(() -> receive(sku, 1));
		try {
			arriving.get(5, TimeUnit.SECONDS);
		}
		finally {
			finish.countDown();
			leaving.get(30, TimeUnit.SECONDS);
			pool.shutdown();
		}

		assertThat(statusOf("shopper-a", sku)).isEqualTo("CANCELLED");
		assertThat(noticesFor(sku)).containsExactly("shopper-b");
	}

	// --- leaving -----------------------------------------------------------------------------------

	@Test
	@DisplayName("leaving gives the place up: the next unit goes to whoever is behind")
	void leavingMovesTheQueue() {
		String sku = soldOut();
		String a = (String) join("shopper-a", sku).getBody().get("id");
		join("shopper-b", sku);
		assertThat(entryOf("shopper-b", sku)).containsEntry("ahead", 1);

		assertThat(leave("shopper-a", a).getBody()).containsEntry("status", "CANCELLED");
		assertThat(entryOf("shopper-b", sku)).containsEntry("ahead", 0);
		// Twice is harmless.
		assertThat(leave("shopper-a", a).getStatusCode()).isEqualTo(HttpStatus.OK);

		receive(sku, 1);

		assertThat(noticesFor(sku)).containsExactly("shopper-b");
	}

	@Test
	@DisplayName("nobody else's place can be given up, and being told cannot be undone")
	void leaveIsYoursAndOnlyBeforeNotice() {
		String sku = soldOut();
		String a = (String) join("shopper-a", sku).getBody().get("id");

		// The same answer as an id that does not exist, so ids cannot be probed.
		assertThat(leave("shopper-b", a).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(statusOf("shopper-a", sku)).isEqualTo("WAITING");

		receive(sku, 1);
		ResponseEntity<Map> tooLate = leave("shopper-a", a);
		assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(tooLate.getBody()).containsEntry("code", "ALREADY_NOTIFIED");
	}

	@Test
	@DisplayName("a shopper whose held unit lapsed joins again at the back, behind those still waiting")
	void rejoiningGoesToTheBack() {
		String sku = soldOut();
		join("shopper-a", sku);
		join("shopper-b", sku);
		join("shopper-c", sku);
		receive(sku, 1);
		assertThat(statusOf("shopper-a", sku)).isEqualTo("NOTIFIED");

		// A never checked out. The unit passes down the queue to B, and A starts again behind C.
		lapseHoldOf("shopper-a", sku);
		assertThat(statusOf("shopper-b", sku)).isEqualTo("NOTIFIED");
		ResponseEntity<Map> rejoined = join("shopper-a", sku);

		assertThat(rejoined.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(rejoined.getBody()).containsEntry("ahead", 1);
	}

	// --- the held unit (ADR 0045) -------------------------------------------------------------------

	@Test
	@DisplayName("being told holds a unit: the shopper told has it, and a faster stranger does not")
	void toldMeansHeld() {
		String sku = soldOut();
		join("shopper-a", sku);

		StockResponse afterReceive = rest.postForObject("/api/v1/inventory/stock/" + sku + "/receive",
				new ReceiveStockRequest(1, "back"), StockResponse.class);

		// The receive's own answer already shows the unit held, not free -- it is re-read after the
		// hold is made through an UPDATE the entity never saw.
		assertThat(afterReceive.reserved()).isEqualTo(1);
		assertThat(afterReceive.available()).isZero();
		assertThat(entryOf("shopper-a", sku).get("heldUntil")).isNotNull();

		// Somebody who never queued is refused. Before ADR 0045 they won this race.
		ResponseEntity<Map> stranger = reserveExpectingFailure(uniqueKey("order"), "stranger", null, sku, 1);
		assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(stranger.getBody()).containsEntry("code", "INSUFFICIENT_STOCK");
	}

	@Test
	@DisplayName("the shopper's checkout adopts the held unit instead of taking another")
	void checkoutAdoptsTheHold() {
		String sku = soldOut();
		join("shopper-a", sku);
		receive(sku, 1);
		String holdKey = "waitlist:" + entryOf("shopper-a", sku).get("id");

		ResponseEntity<ReservationResponse> order = reserve(uniqueKey("order"), "shopper-a", sku, 1);

		assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(order.getBody().status()).isEqualTo(ReservationStatus.HELD);
		// Still one unit reserved, not two: the order took over the hold rather than adding to it.
		assertStock(sku, 1, 1);
		assertThat(reservationStatus(holdKey)).isEqualTo("ADOPTED");
		// And the hold is no longer offered: the shopper has their unit, inside their order.
		assertThat(entryOf("shopper-a", sku).get("heldUntil")).isNull();

		// The ledger still replays to the balance through hold, adoption and sale.
		rest.postForObject("/api/v1/inventory/reservations/" + order.getBody().reservationKey() + "/commit",
				null, ReservationResponse.class);
		assertStock(sku, 0, 0);
		assertThat(ledgerSum(sku, "on_hand_delta")).isZero();
		assertThat(ledgerSum(sku, "reserved_delta")).isZero();
	}

	@Test
	@DisplayName("adopting moves nothing, so it tells nobody else in the queue")
	void adoptionIsNotUnitsComingBack() {
		String sku = soldOut();
		join("shopper-a", sku);
		join("shopper-b", sku);
		receive(sku, 1);

		reserve(uniqueKey("order"), "shopper-a", sku, 1);

		// Had adoption been written as a release and a reserve, the release would have told B about a
		// unit that never left A's hands.
		assertThat(statusOf("shopper-b", sku)).isEqualTo("WAITING");
		assertThat(noticesFor(sku)).containsExactly("shopper-a");
	}

	@Test
	@DisplayName("ordering more than is held adopts the held unit and takes the rest from stock")
	void adoptionCoversPartOfALine() {
		String sku = soldOut();
		join("shopper-a", sku);
		receive(sku, 3);
		assertStock(sku, 3, 1);

		reserve(uniqueKey("order"), "shopper-a", sku, 2);

		// One adopted, one fresh: two reserved in all, not three.
		assertStock(sku, 3, 2);
	}

	@Test
	@DisplayName("an unused hold lapses, the unit passes to the next in line, and the order service is not told")
	void lapsedHoldPassesDownTheQueue() {
		String sku = soldOut();
		join("shopper-a", sku);
		join("shopper-b", sku);
		receive(sku, 1);
		String holdKey = "waitlist:" + entryOf("shopper-a", sku).get("id");

		lapseHoldOf("shopper-a", sku);

		assertThat(reservationStatus(holdKey)).isEqualTo("EXPIRED");
		assertThat(statusOf("shopper-b", sku)).isEqualTo("NOTIFIED");
		assertThat(entryOf("shopper-b", sku).get("heldUntil")).isNotNull();
		assertStock(sku, 1, 1);
		// A waitlist hold is no order's. ReservationExpired carries the key the order service parses as an
		// order id, so announcing this one would fail there and dead-letter.
		assertThat(jdbc.queryForObject("""
				select count(*) from outbox_messages
				 where event_type = 'ReservationExpired' and payload->>'reservationKey' = ?""",
				Integer.class, holdKey)).isZero();
	}

	@Test
	@DisplayName("somebody else's checkout never adopts a hold that is not theirs")
	void onlyTheCustomerAdopts() {
		String sku = soldOut();
		join("shopper-a", sku);
		receive(sku, 1);
		String holdKey = "waitlist:" + entryOf("shopper-a", sku).get("id");

		ResponseEntity<Map> stranger = reserveExpectingFailure(uniqueKey("order"), "shopper-b", null, sku, 1);

		assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(reservationStatus(holdKey)).isEqualTo("HELD");
	}

	@Test
	@DisplayName("a flash-sale checkout adopts the hold and is still charged to the sale and the cap")
	void saleCheckoutAdoptsAndStillCounts() {
		String sku = uniqueSku("WAIT");
		UUID saleId = UUID.randomUUID();
		createStock(sku, 5);
		allocate(saleId, sku, 5, 1);
		// Sell the warehouse out from under the sale, then bring one unit back for the queue.
		reserve(uniqueKey("order"), "bulk", sku, 5);
		join("shopper-a", sku);
		receive(sku, 1);

		ResponseEntity<ReservationResponse> order = reserve(uniqueKey("order"), "shopper-a", saleId, sku, 1, null);

		assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertStock(sku, 6, 6);
		AllocationResponse allocation = rest.getForObject(
				"/api/v1/inventory/allocations/" + saleId + "/" + sku, AllocationResponse.class);
		assertThat(allocation.reservedUnits()).isEqualTo(1);
		// The cap still binds: a second sale unit for the same shopper is refused.
		ResponseEntity<Map> second = reserveExpectingFailure(uniqueKey("order"), "shopper-a", saleId, sku, 1);
		assertThat(second.getBody()).containsEntry("code", "CUSTOMER_LIMIT_EXCEEDED");
	}

	// --- helpers -----------------------------------------------------------------------------------

	/** Ages a shopper's waitlist hold past its expiry and sweeps, as if they never checked out. */
	private void lapseHoldOf(String customerId, String sku) {
		String holdKey = "waitlist:" + entryOf(customerId, sku).get("id");
		assertThat(jdbc.update("update reservations set expires_at = now() - interval '1 minute' "
				+ "where reservation_key = ? and status = 'HELD'", holdKey)).isEqualTo(1);
		expiryService.sweepBatch();
	}

	private String reservationStatus(String reservationKey) {
		return jdbc.queryForObject("select status from reservations where reservation_key = ?", String.class,
				reservationKey);
	}

	/** The sum of one delta column over every movement of a SKU: what the ledger says the balance is. */
	private int ledgerSum(String sku, String column) {
		return jdbc.queryForObject("select coalesce(sum(" + column + "), 0) from stock_movements where sku = ?",
				Integer.class, sku);
	}

	/** A tracked SKU with nothing available. */
	private String soldOut() {
		String sku = uniqueSku("WAIT");
		createStock(sku, 0);
		return sku;
	}

	private HttpHeaders as(String customerId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION,
				"Bearer " + tokens.issue(customerId, customerId + "@example.test", List.of()));
		return headers;
	}

	private ResponseEntity<Map> join(String customerId, String sku) {
		return rest.exchange("/api/v1/inventory/waitlist", HttpMethod.POST,
				new HttpEntity<>(Map.of("sku", sku), as(customerId)), Map.class);
	}

	private ResponseEntity<Map> leave(String customerId, String entryId) {
		return rest.exchange("/api/v1/inventory/waitlist/" + entryId, HttpMethod.DELETE,
				new HttpEntity<>(as(customerId)), Map.class);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> entryOf(String customerId, String sku) {
		List<Map<String, Object>> mine = rest.exchange("/api/v1/inventory/waitlist/mine", HttpMethod.GET,
				new HttpEntity<>(as(customerId)), List.class).getBody();
		return mine.stream().filter(entry -> sku.equals(entry.get("sku"))).findFirst().orElseThrow();
	}

	private String statusOf(String customerId, String sku) {
		return (String) entryOf(customerId, sku).get("status");
	}

	private void receive(String sku, int quantity) {
		assertThat(rest.postForEntity("/api/v1/inventory/stock/" + sku + "/receive",
				new ReceiveStockRequest(quantity, "back in stock"), Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	/** Who has been told about this SKU, from the outbox -- the notice itself, not the entry's status. */
	private List<String> noticesFor(String sku) {
		return jdbc.queryForList("""
				select payload->>'customerId'
				  from outbox_messages
				 where event_type = ?
				   and payload->>'sku' = ?""", String.class, BackInStock.TYPE, sku);
	}
}
