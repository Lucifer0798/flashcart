package com.flashcart.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.inventory.api.dto.AllocationRequest;
import com.flashcart.inventory.api.dto.AllocationResponse;
import com.flashcart.inventory.api.dto.CreateStockRequest;
import com.flashcart.inventory.api.dto.ReservationResponse;
import com.flashcart.inventory.api.dto.ReserveRequest;
import com.flashcart.inventory.api.dto.StockResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import com.flashcart.common.security.AccessTokens;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared setup for the inventory integration tests.
 *
 * <p>One PostgreSQL container and one Spring context across every subclass — the annotations are
 * identical, so Spring's context cache reuses it rather than paying for a second boot and a second
 * container.
 *
 * <p>Not H2, for reasons that matter more here than anywhere else in the platform: this service's
 * correctness is built on {@code SELECT ... FOR UPDATE SKIP LOCKED},
 * {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE}, real row-level locking under genuine
 * concurrency, and CHECK constraints as the last line of defence. An in-memory database that
 * approximates those would produce a green suite that proves nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
		// The sweeper is driven explicitly in the tests that care about it. Left running on its own
		// timer it would reclaim holds mid-assertion and make unrelated tests flaky for reasons that
		// have nothing to do with what they are testing.
		"flashcart.inventory.sweeper.enabled=false",
		// The concurrency test deliberately floods the service. The production 2s connection timeout
		// is there to shed load; here it would turn contention into 500s and hide the real result.
		"spring.datasource.hikari.connection-timeout=30000",
		"spring.datasource.hikari.maximum-pool-size=20",
		// Logs a stack trace for any connection held longer than this. On by default in the suite
		// rather than switched on during an investigation: a connection leak here would show up as
		// "every test after the concurrent one times out", which looks like flakiness and is not.
		"spring.datasource.hikari.leak-detection-threshold=10000"
})
abstract class AbstractInventoryIT {

	/**
	 * Started once for the whole JVM and never stopped explicitly — the singleton container pattern,
	 * deliberately <em>not</em> JUnit's {@code @Testcontainers}/{@code @Container} lifecycle.
	 *
	 * <p>{@code @Container} on a static field ties the container's life to <em>one test class</em>:
	 * JUnit stops it when that class finishes. Spring's context cache does not agree — it keeps the
	 * same application context, and the same Hikari pool, alive for the next class with matching
	 * annotations. So the second IT class to run would inherit a pool pointed at a container that no
	 * longer exists, and every single one of its tests would hang until the connection timeout.
	 *
	 * <p>That is not hypothetical: it is exactly what happened here. {@code InventoryConcurrencyIT}
	 * passed, stopped the container on its way out, and then all 27 {@code InventoryIT} tests failed
	 * at precisely 30.0s each — while passing when run on their own. Starting the container in a
	 * static initialiser makes its lifetime the JVM's, and Ryuk still cleans it up at exit.
	 */
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	/**
	 * A real Redis, so the whole suite — including the concurrency proof — runs with the availability
	 * gate switched on.
	 *
	 * <p>This is the important placement. The gate sits in front of the reserve path, and the only
	 * claim that matters about it is that it cannot break the no-oversell guarantee. Running the
	 * concurrency suite without it would leave that claim untested, and running it against a fake
	 * Redis would test the fake.
	 */
	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
			.withExposedPorts(6379);

	static {
		POSTGRES.start();
		REDIS.start();
	}

	@Autowired
	protected TestRestTemplate rest;

	@Autowired
	protected AccessTokens tokens;

	@Autowired
	protected StringRedisTemplate redis;

	/** Set once per JVM: the shared Lettuce connection outlives every context and every class. */
	private static boolean redisConnectionEstablished;

	/**
	 * Pays for the lazy Lettuce handshake here, rather than inside whichever test happens to issue
	 * the first Redis command.
	 *
	 * <p>Lettuce connects on first use and bounds <em>connection initialisation</em> by the command
	 * timeout — 250ms, set deliberately, because ADR 0016 wants the gate to give up faster than
	 * PostgreSQL would have answered rather than hold up a reservation. (Not the connect timeout:
	 * raising {@code spring.data.redis.connect-timeout} leaves the failure reading "timed out after
	 * 250 millisecond(s)" unchanged.) A container handshake on a loaded machine does not fit in
	 * 250ms — but only the first attempt pays it, and every command after it succeeds against the
	 * established connection.
	 *
	 * <p>Absorbed here instead of by relaxing the timeout in test scope, so the suite goes on running
	 * against exactly the production values. Two things went wrong without it, and only one of them
	 * was visible: {@code AvailabilityGateIT} failed on Redis connectivity rather than on the thing
	 * it asserts, and — silently — the first reserve of the run executed with the gate unavailable,
	 * because {@link com.flashcart.inventory.service.RedisAvailabilityGate} swallows that error into
	 * {@code UNKNOWN}. A suite whose first gate assertion is decided by a handshake is not testing
	 * the gate.
	 *
	 * <p>Bounded, so this cannot hide a Redis that is genuinely gone: five refusals and the
	 * exception is rethrown, failing the test with the connection error rather than with whatever
	 * confusing shape a missing counter takes later.
	 */
	/**
	 * Whether this class expects to be able to reach Redis at all.
	 *
	 * <p>Overridden by the one class that points the gate at a dead port on purpose: warming a
	 * connection that is meant to be refused would fail the run here, in setup, before the test could
	 * assert anything about the refusal being handled.
	 */
	protected boolean redisIsReachable() {
		return true;
	}

	@BeforeEach
	void establishTheRedisConnection() {
		if (redisConnectionEstablished || !redisIsReachable()) {
			return;
		}
		for (int attempt = 1; ; attempt++) {
			try {
				// Any command will do; this one cannot disturb a counter.
				redis.hasKey("flashcart:avail:connection-warm-up");
				redisConnectionEstablished = true;
				return;
			}
			catch (DataAccessException ex) {
				if (attempt == 5) {
					throw ex;
				}
			}
		}
	}

	/**
	 * Signs every request in this hierarchy as an operator.
	 *
	 * <p>Inventory's write surface needs one since ADR 0022 -- receiving stock and adjusting a ledger
	 * are not things being signed in should authorise. Done once here rather than in five subclasses,
	 * because five copies is five chances for one of them to quietly stop exercising the service and
	 * start exercising the filter instead.
	 */
	@BeforeEach
	void signInAsOperator() {
		String token = tokens.issue("ops-test", "ops@example.test", List.of(AccessTokens.OPERATOR));
		rest.getRestTemplate().getInterceptors().clear();
		rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
			// Only when the caller has not said who it is, matching the payment and shipping bases.
			// A test that deliberately acts as a shopper would otherwise be silently promoted to an
			// operator and pass for the wrong reason.
			if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
				request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			}
			return execution.execute(request, body);
		});
	}

	// --- fixtures --------------------------------------------------------------------------------

	/** A SKU nothing else in the suite will touch, so tests never contend over shared fixtures. */
	protected static String uniqueSku(String prefix) {
		return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
	}

	protected static String uniqueKey(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	protected StockResponse createStock(String sku, int quantity) {
		ResponseEntity<StockResponse> response = rest.postForEntity("/api/v1/inventory/stock",
				new CreateStockRequest(sku, quantity, "created by the test suite"), StockResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	protected AllocationResponse allocate(UUID flashSaleId, String sku, int units, int perCustomerLimit) {
		ResponseEntity<AllocationResponse> response = rest.postForEntity("/api/v1/inventory/allocations",
				new AllocationRequest(flashSaleId, sku, units, perCustomerLimit), AllocationResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	protected ResponseEntity<ReservationResponse> reserve(String key, String customerId, String sku, int quantity) {
		return reserve(key, customerId, null, sku, quantity, null);
	}

	protected ResponseEntity<ReservationResponse> reserve(String key, String customerId, UUID flashSaleId,
			String sku, int quantity, Integer ttlSeconds) {
		return rest.postForEntity("/api/v1/inventory/reservations",
				new ReserveRequest(key, customerId, flashSaleId, ttlSeconds,
						List.of(new ReserveRequest.Line(sku, quantity))),
				ReservationResponse.class);
	}

	/** The raw status of a reserve attempt, for tests that expect it to be refused. */
	protected ResponseEntity<Map> reserveExpectingFailure(String key, String customerId, UUID flashSaleId,
			String sku, int quantity) {
		return rest.postForEntity("/api/v1/inventory/reservations",
				new ReserveRequest(key, customerId, flashSaleId, null,
						List.of(new ReserveRequest.Line(sku, quantity))),
				Map.class);
	}

	protected StockResponse stock(String sku) {
		return rest.getForObject("/api/v1/inventory/stock/" + sku, StockResponse.class);
	}

	/** Asserts the position, and — the point of the whole service — that it is not oversold. */
	protected void assertStock(String sku, int expectedOnHand, int expectedReserved) {
		StockResponse position = stock(sku);
		assertThat(position.onHand()).as("on hand for %s", sku).isEqualTo(expectedOnHand);
		assertThat(position.reserved()).as("reserved for %s", sku).isEqualTo(expectedReserved);
		assertThat(position.available()).as("available for %s", sku)
				.isEqualTo(expectedOnHand - expectedReserved);
		assertThat(position.reserved()).as("%s must never be oversold", sku)
				.isLessThanOrEqualTo(position.onHand());
	}
}
