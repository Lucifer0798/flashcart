package com.flashcart.inventory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.common.security.OperatorAccessRetention;
import com.flashcart.inventory.api.dto.ReservationResponse;
import com.flashcart.inventory.api.dto.ReserveRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff reading a customer's reservations, and the record of it. See ADR 0067.
 *
 * <p>Every read here is of a customer who really has a reservation, because that is what nothing tested: the
 * endpoint answered 500 for exactly those customers and 200 for everybody else, for as long as it existed.
 */
class OperatorAccessLogIT extends AbstractInventoryIT {

	private static final String RESERVATIONS = "/api/v1/inventory/reservations";

	private static final String LOG = "/api/v1/inventory/_access-log";

	private static final String MINE = "/api/v1/inventory/_access-log/mine";

	/** What only {@code OperatorFilter} says, so a test can tell the filter's refusal from the reader's. */
	private static final String FILTER_REFUSAL = "This operation requires an operator account";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MeterRegistry meters;

	@Autowired
	OperatorAccessRetention retention;

	@Autowired
	ScheduledTaskHolder scheduled;

	// --- the read itself ------------------------------------------------------------------------------

	@Test
	@DisplayName("the warehouse reads a customer's reservations, each with all its lines, newest first")
	@SuppressWarnings("unchecked")
	void theReadAnswersForACustomerWithReservations() {
		String customer = customer("reader");
		String first = uniqueSku("LOG-A");
		String second = uniqueSku("LOG-B");
		createStock(first, 10);
		createStock(second, 10);
		String older = uniqueKey("older");
		String newer = uniqueKey("newer");
		assertThat(reserve(older, customer, first, 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// Two lines on the one reservation: a fetch join returning a row per line must still be one reservation.
		assertThat(rest.postForEntity(RESERVATIONS, new ReserveRequest(newer, customer, null, null,
				List.of(new ReserveRequest.Line(first, 2), new ReserveRequest.Line(second, 3))),
				ReservationResponse.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(reserve(uniqueKey("other"), customer("somebody-else"), first, 1).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		ResponseEntity<List> read = read(customer, role(AccessTokens.WAREHOUSE));

		assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> reservations = read.getBody();
		assertThat(reservations).extracting(r -> r.get("reservationKey")).containsExactly(newer, older);
		assertThat(reservations).allSatisfy(r -> assertThat(r).containsEntry("customerId", customer));
		assertThat((List<Map<String, Object>>) reservations.get(0).get("lines"))
				.extracting(line -> line.get("sku") + "x" + line.get("quantity"))
				.containsExactlyInAnyOrder(first + "x2", second + "x3");
		assertThat((List<Map<String, Object>>) reservations.get(1).get("lines"))
				.extracting(line -> line.get("sku") + "x" + line.get("quantity"))
				.containsExactly(first + "x1");
	}

	@Test
	@DisplayName("that read is recorded: who, what, whose, and the request it belonged to")
	void theReadIsRecorded() {
		String customer = customerWithAReservation("recorded");

		assertThat(read(customer, role(AccessTokens.WAREHOUSE)).getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> row = jdbc.queryForMap("select * from operator_access_log where customer_id = ?", customer);
		assertThat(row).containsEntry("operator_id", "staff-" + AccessTokens.WAREHOUSE)
				.containsEntry("action", "READ_RESERVATION_LIST")
				.containsEntry("resource_id", null);
		assertThat(row.get("correlation_id")).isNotNull();
	}

	@Test
	@DisplayName("an operator's read is recorded the same way")
	void anOperatorsReadIsRecorded() {
		String customer = customerWithAReservation("operator");

		assertThat(read(customer, role(AccessTokens.OPERATOR)).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(rows(customer)).isEqualTo(1);
	}

	@Test
	@DisplayName("looking at somebody with nothing is still looking, so it is recorded")
	void anEmptyAnswerIsStillRecorded() {
		String customer = customer("empty");

		ResponseEntity<List> read = read(customer, role(AccessTokens.WAREHOUSE));

		assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(read.getBody()).isEmpty();
		assertThat(rows(customer)).isEqualTo(1);
	}

	@Test
	@DisplayName("a member of staff reading their own leaves nothing: that is a customer reading their own")
	void readingYourOwnIsNotRecorded() {
		String staff = "staff-" + AccessTokens.WAREHOUSE;
		createAReservationFor(staff);
		long before = rows(staff);

		assertThat(read(staff, role(AccessTokens.WAREHOUSE)).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(rows(staff)).isEqualTo(before);
	}

	@Test
	@DisplayName("naming nobody is refused, and leaves no record of reading nobody")
	void aBlankCustomerIsRefused() {
		ResponseEntity<Map> refused = rest.exchange(RESERVATIONS + "?customerId=", HttpMethod.GET,
				new HttpEntity<>(role(AccessTokens.WAREHOUSE)), Map.class);

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(rows("")).isZero();
	}

	@Test
	@DisplayName("if the read cannot be recorded, nothing about the customer is shown")
	void anUnrecordableReadIsRefused() {
		String customer = customerWithAReservation("unrecordable");
		double failuresBefore = meters.counter("flashcart.operator.read.record.failures").count();

		// Renamed rather than dropped, so the suite continues afterwards.
		jdbc.execute("alter table operator_access_log rename to operator_access_log_hidden");
		try {
			ResponseEntity<String> response = rest.exchange(RESERVATIONS + "?customerId=" + customer, HttpMethod.GET,
					new HttpEntity<>(role(AccessTokens.WAREHOUSE)), String.class);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(response.getBody()).doesNotContain("reservationKey", "LOG-KEPT");
		}
		finally {
			jdbc.execute("alter table operator_access_log_hidden rename to operator_access_log");
		}
		assertThat(meters.counter("flashcart.operator.read.record.failures").count()).isEqualTo(failuresBefore + 1);
	}

	@Test
	@DisplayName("the record comes before the data: a read that then fails is still on the record")
	void theRecordIsWrittenBeforeTheRead() {
		String customer = customerWithAReservation("before");

		jdbc.execute("alter table reservation_lines rename to reservation_lines_hidden");
		try {
			assertThat(status(RESERVATIONS + "?customerId=" + customer, role(AccessTokens.WAREHOUSE)).is5xxServerError()).isTrue();
		}
		finally {
			jdbc.execute("alter table reservation_lines_hidden rename to reservation_lines");
		}
		assertThat(rows(customer)).isEqualTo(1);
	}

	// --- who may make it ------------------------------------------------------------------------------

	@Test
	@DisplayName("a customer cannot read reservations, not even their own, and a refused read records nothing")
	void aCustomerIsRefused() {
		String customer = customerWithAReservation("shopper");

		assertThat(status(RESERVATIONS + "?customerId=" + customer, shopper(customer))).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(RESERVATIONS + "?customerId=" + customer, shopper("a-stranger"))).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(RESERVATIONS + "?customerId=" + customer, forged())).isEqualTo(HttpStatus.UNAUTHORIZED);

		assertThat(rows(customer)).isZero();
	}

	@Test
	@DisplayName("support cannot make this read: the capability to see customers is not a path rule here")
	void supportIsNotGivenThisRead() {
		String customer = customerWithAReservation("support");

		assertThat(status(RESERVATIONS + "?customerId=" + customer, role(AccessTokens.SUPPORT)))
				.isEqualTo(HttpStatus.FORBIDDEN);

		assertThat(rows(customer)).isZero();
	}

	@Test
	@DisplayName("naming the warehouse's paths lost it none of the fifteen endpoints it had")
	void theWarehouseKeepsEveryEndpoint() {
		String sku = "NO-SUCH-SKU";
		UUID sale = UUID.randomUUID();
		List<Map.Entry<HttpMethod, String>> fifteen = List.of(
				Map.entry(HttpMethod.GET, "/api/v1/inventory/stock"),
				Map.entry(HttpMethod.POST, "/api/v1/inventory/stock"),
				Map.entry(HttpMethod.POST, "/api/v1/inventory/stock/" + sku + "/receive"),
				Map.entry(HttpMethod.POST, "/api/v1/inventory/stock/" + sku + "/adjust"),
				Map.entry(HttpMethod.GET, "/api/v1/inventory/stock/" + sku + "/movements"),
				Map.entry(HttpMethod.GET, "/api/v1/inventory/stock/movements/reservation/" + UUID.randomUUID()),
				Map.entry(HttpMethod.GET, "/api/v1/inventory/allocations/" + sale),
				Map.entry(HttpMethod.GET, "/api/v1/inventory/allocations/" + sale + "/" + sku),
				Map.entry(HttpMethod.POST, "/api/v1/inventory/allocations"),
				Map.entry(HttpMethod.PUT, "/api/v1/inventory/allocations/" + sale + "/" + sku),
				Map.entry(HttpMethod.POST, RESERVATIONS),
				Map.entry(HttpMethod.GET, RESERVATIONS + "/no-such-key"),
				Map.entry(HttpMethod.GET, RESERVATIONS + "?customerId=" + customer("fifteen")),
				Map.entry(HttpMethod.POST, RESERVATIONS + "/no-such-key/commit"),
				Map.entry(HttpMethod.POST, RESERVATIONS + "/no-such-key/release"));

		// Empty bodies and unknown ids, so each is refused by the handler and moves nothing. What matters is who
		// refused: the filter answers 403 and no handler in this service does.
		HttpHeaders warehouse = role(AccessTokens.WAREHOUSE);
		warehouse.setContentType(MediaType.APPLICATION_JSON);
		assertThat(fifteen).allSatisfy(endpoint -> {
			HttpEntity<String> request = endpoint.getKey() == HttpMethod.GET
					? new HttpEntity<>(role(AccessTokens.WAREHOUSE))
					: new HttpEntity<>("{}", warehouse);
			assertThat(rest.exchange(endpoint.getValue(), endpoint.getKey(), request, String.class).getStatusCode())
					.as("%s %s", endpoint.getKey(), endpoint.getValue())
					.isNotEqualTo(HttpStatus.FORBIDDEN);
		});
	}

	// --- support's reader -----------------------------------------------------------------------------

	@Test
	@DisplayName("support sees who read a customer's reservations, and their own read of that, newest first")
	void supportReadsTheLog() {
		String customer = customerWithAReservation("audited");
		read(customer, role(AccessTokens.WAREHOUSE));

		ResponseEntity<List> log = rest.exchange(LOG + "?customerId=" + customer, HttpMethod.GET,
				new HttpEntity<>(role(AccessTokens.SUPPORT)), List.class);

		assertThat(log.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> entries = log.getBody();
		assertThat(entries).extracting(e -> e.get("action")).containsExactly("READ_ACCESS_LOG", "READ_RESERVATION_LIST");
		assertThat(entries).extracting(e -> e.get("operatorId"))
				.containsExactly("staff-" + AccessTokens.SUPPORT, "staff-" + AccessTokens.WAREHOUSE);
	}

	@Test
	@DisplayName("an operator may read the log as well, being a superset of support")
	void anOperatorReadsTheLog() {
		assertThat(status(LOG + "?customerId=" + customer("op-log"), role(AccessTokens.OPERATOR)))
				.isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("the warehouse may not read who looked, and the filter is what stops it")
	void theWarehouseCannotReadTheLog() {
		String customer = customerWithAReservation("not-the-warehouse");

		ResponseEntity<Map> refused = rest.exchange(LOG + "?customerId=" + customer, HttpMethod.GET,
				new HttpEntity<>(role(AccessTokens.WAREHOUSE)), Map.class);

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		// The reader refuses too, with its own message, so a 403 alone would survive the filter rule going.
		assertThat(refused.getBody()).containsEntry("message", FILTER_REFUSAL);
		assertThat(rows(customer)).isZero();
	}

	@Test
	@DisplayName("a customer may not read support's view, even of themselves")
	void aCustomerCannotReadSupportsView() {
		String customer = customer("own-view");

		assertThat(status(LOG + "?customerId=" + customer, shopper(customer))).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rows(customer)).isZero();
	}

	// --- the customer's reader ------------------------------------------------------------------------

	@Test
	@DisplayName("a customer sees that staff looked, what at and when, and not who")
	void aCustomerSeesTheirOwn() {
		String customer = customerWithAReservation("mine");
		String stranger = customerWithAReservation("not-mine");
		read(customer, role(AccessTokens.WAREHOUSE));
		read(stranger, role(AccessTokens.WAREHOUSE));

		// The parameter is ignored: whose comes from the token.
		ResponseEntity<List> mine = rest.exchange(MINE + "?customerId=" + stranger, HttpMethod.GET,
				new HttpEntity<>(shopper(customer)), List.class);

		assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> entries = mine.getBody();
		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry).containsOnlyKeys("action", "description", "resourceId", "readAt");
			assertThat(entry).containsEntry("action", "READ_RESERVATION_LIST")
					.containsEntry("description", "A member of staff viewed your reservations");
		});
		// And reading it is a customer reading their own, so it adds nothing.
		assertThat(rows(customer)).isEqualTo(1);
	}

	@Test
	@DisplayName("the customer's reader needs a token that verifies")
	void theCustomersReaderNeedsAToken() {
		assertThat(status(MINE, forged())).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// --- how long it is kept (ADR 0026) ---------------------------------------------------------------

	@Test
	@DisplayName("the sweep runs on a schedule here, and with nothing configured it deletes nothing")
	void theSweepIsScheduledAndKeepsEverythingByDefault() {
		String customer = customer("kept");
		recordAged(customer, 4000);

		assertThat(scheduled.getScheduledTasks()).extracting(task -> task.getTask().getRunnable().toString())
				.anyMatch(task -> task.contains("OperatorAccessRetention.prune"));
		assertThat(retention.sweep()).isZero();
		assertThat(rows(customer)).isEqualTo(1);
	}

	@Test
	@DisplayName("a configured window deletes what is older and keeps what is not, a batch at a time")
	void aConfiguredWindowDeletesOnlyWhatIsOlder() {
		// A table-wide delete, so start empty: otherwise the counts depend on what other tests left behind.
		jdbc.update("delete from operator_access_log");
		String old = customer("old");
		String recent = customer("recent");
		recordAged(old, 100);
		recordAged(old, 200);
		recordAged(old, 300);
		recordAged(recent, 10);

		OperatorAccessRetention sweeper = new OperatorAccessRetention(jdbc, Duration.ofDays(30), 2);

		assertThat(sweeper.sweep()).isEqualTo(2);
		assertThat(sweeper.sweep()).isEqualTo(1);
		assertThat(sweeper.sweep()).isZero();
		assertThat(rows(old)).isZero();
		assertThat(rows(recent)).isEqualTo(1);
	}

	// --- fixtures -------------------------------------------------------------------------------------

	private static String customer(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private String customerWithAReservation(String prefix) {
		String customer = customer(prefix);
		createAReservationFor(customer);
		return customer;
	}

	private void createAReservationFor(String customer) {
		String sku = uniqueSku("LOG-KEPT");
		createStock(sku, 5);
		assertThat(reserve(uniqueKey("log"), customer, sku, 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private ResponseEntity<List> read(String customer, HttpHeaders as) {
		return rest.exchange(RESERVATIONS + "?customerId=" + customer, HttpMethod.GET, new HttpEntity<>(as), List.class);
	}

	private HttpStatus status(String path, HttpHeaders as) {
		return HttpStatus.valueOf(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(as), String.class)
				.getStatusCode().value());
	}

	private long rows(String customer) {
		return jdbc.queryForObject("select count(*) from operator_access_log where customer_id = ?", Long.class, customer);
	}

	private void recordAged(String customer, int daysAgo) {
		jdbc.update("""
				insert into operator_access_log (operator_id, action, customer_id, read_at)
				values ('ops-retention', 'READ_RESERVATION_LIST', ?, now() - make_interval(days => ?))""",
				customer, daysAgo);
	}

	/** A member of staff holding exactly this role, so the OPERATOR superset cannot mask a wrong rule (ADR 0039). */
	private HttpHeaders role(String role) {
		return bearer(tokens.issue("staff-" + role, role + "@example.test", List.of(role)));
	}

	private HttpHeaders shopper(String customerId) {
		return bearer(tokens.issue(customerId, "shopper@example.test", List.of()));
	}

	/** Forged rather than absent, because this suite's template signs as an operator when given no header at all. */
	private static HttpHeaders forged() {
		return bearer("not-a-real-token");
	}

	private static HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		return headers;
	}
}
