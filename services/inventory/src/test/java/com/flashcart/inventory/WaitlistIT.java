package com.flashcart.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.flashcart.common.event.message.BackInStock;
import com.flashcart.inventory.api.dto.ReceiveStockRequest;
import com.flashcart.inventory.api.dto.ReservationResponse;
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
	@DisplayName("a shopper told and too late joins again at the back, behind those still waiting")
	void rejoiningGoesToTheBack() {
		String sku = soldOut();
		join("shopper-a", sku);
		join("shopper-b", sku);
		receive(sku, 1);
		assertThat(statusOf("shopper-a", sku)).isEqualTo("NOTIFIED");

		// The unit went to somebody else in the meantime.
		reserve(uniqueKey("order"), "faster-buyer", sku, 1);
		ResponseEntity<Map> rejoined = join("shopper-a", sku);

		assertThat(rejoined.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(rejoined.getBody()).containsEntry("ahead", 1);
	}

	// --- helpers -----------------------------------------------------------------------------------

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
