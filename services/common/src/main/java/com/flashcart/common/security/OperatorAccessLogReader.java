package com.flashcart.common.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.flashcart.common.error.OperatorRequiredException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Answers "who read my data" from the table {@link OperatorAccessLog} writes.
 *
 * <p>ADR 0025 created the table and said nobody queries it on an ordinary day. Every ADR from 0026 to
 * 0033 then repeated that it had no reader — eight in a row, which is long enough to stop calling it
 * deferred. This is the reader.
 *
 * <h2>One per service, because there is one table per service</h2>
 *
 * The log lives in three databases: order, payment and shipping each write their own on the same
 * connection the read came from, which is what lets a failure to record fail the read (ADR 0025). A
 * single aggregating endpoint would have to call the other two synchronously, and this platform does
 * not do that for a reason.
 *
 * <p>So each service exposes its own, and "who read my data" is three requests. That cost is real and
 * ADR 0028 named it when the third table appeared. The reader does not hide it; it makes each third
 * answerable without a database client.
 *
 * <h2>The read is itself recorded, before it answers</h2>
 *
 * Reading this log is an operator reading information about a customer, so it is recorded like any
 * other — and recorded <em>first</em>, keeping ADR 0025's invariant that the row exists before the
 * data is disclosed. One consequence is worth knowing rather than being surprised by: the newest entry
 * in any response is the request that produced it.
 */
public class OperatorAccessLogReader {

	/** What an operator reading this log is recorded as having done. */
	public static final String ACTION = "READ_ACCESS_LOG";

	private static final String BY_CUSTOMER = """
			select id, operator_id, action, resource_id, customer_id, correlation_id, read_at
			  from operator_access_log
			 where customer_id = ?
			 order by read_at desc, id desc
			 limit ?""";

	private final JdbcTemplate jdbc;
	private final CallerIdentity caller;
	private final OperatorAccessLog accessLog;

	public OperatorAccessLogReader(JdbcTemplate jdbc, CallerIdentity caller,
			OperatorAccessLog accessLog) {
		this.jdbc = jdbc;
		this.caller = caller;
		this.accessLog = accessLog;
	}

	/**
	 * @param id         the row, so a reader can cite a specific access
	 * @param operatorId who looked. The token subject, not an email — see the table comment.
	 * @param resourceId which row, or null when it was a listing with no single subject
	 */
	public record Entry(long id, String operatorId, String action, String resourceId,
			String customerId, String correlationId, Instant readAt) {
	}

	/**
	 * Every recorded operator access to one customer's data in this service, newest first.
	 *
	 * <p>Operator-only, and deliberately <strong>not</strong> routed through
	 * {@link CustomerDataAccess#mayRead}, which would also grant the customer named in the query.
	 * Whether a shopper should see which staff opened their order was a genuine question and not one to
	 * answer as a side effect of building the reader. ADR 0056 answered it, separately: see {@link #mine}.
	 *
	 * @throws OperatorRequiredException when the caller is signed in but is not an operator
	 */
	public List<Entry> forCustomer(String authorization, String customerId, int limit) {
		String operatorId = caller.require(authorization);
		if (!caller.maySeeAnotherCustomer(authorization)) {
			// 403 rather than 404. The oracle argument that makes ORDER numbers a 404 does not apply:
			// this path is fixed and published, and the customer id in the query is one the caller
			// already knew. Refusing plainly is the honest answer. See ADR 0022.
			throw new OperatorRequiredException(
					"Reading who accessed a customer's data requires an operator");
		}

		// Recorded before the query runs, not after. ADR 0025's property is that the record exists
		// before the data is disclosed, and reading this table is a disclosure like any other.
		accessLog.record(operatorId, ACTION, null, customerId);

		return jdbc.query(BY_CUSTOMER, (rs, row) -> new Entry(
				rs.getLong("id"),
				rs.getString("operator_id"),
				rs.getString("action"),
				rs.getString("resource_id"),
				rs.getString("customer_id"),
				rs.getString("correlation_id"),
				rs.getTimestamp("read_at").toInstant()),
				customerId, limit);
	}

	/**
	 * One staff access to the caller's own data, as the customer sees it.
	 *
	 * <p>Says that staff looked, what at and when, and not who: no operator id, and no correlation id that
	 * could be traced to one. Support can still name the person from {@link #forCustomer}. See ADR 0056.
	 *
	 * @param description the action in words, for a person rather than a program
	 * @param resourceId  which of theirs, when it was one thing; null for a listing
	 */
	public record CustomerEntry(String action, String description, String resourceId, Instant readAt) {
	}

	private static final Map<String, String> SAID_AS = Map.of(
			"READ_ORDER", "A member of staff viewed one of your orders",
			"READ_ORDER_HISTORY", "A member of staff viewed the history of one of your orders",
			"READ_ORDER_LIST", "A member of staff viewed your orders",
			"READ_PAYMENT", "A member of staff viewed one of your payments",
			"READ_PAYMENT_LIST", "A member of staff viewed your payments",
			"READ_SHIPMENT", "A member of staff viewed one of your parcels",
			"READ_SHIPMENT_LIST", "A member of staff viewed your parcels",
			"READ_NOTICES", "A member of staff viewed the emails we sent you",
			ACTION, "A member of staff viewed this record of who has looked at your data");

	private static final String MINE = """
			select action, resource_id, read_at
			  from operator_access_log
			 where customer_id = ?
			 order by read_at desc, id desc
			 limit ?""";

	/**
	 * Every recorded staff access to the caller's own data in this service, newest first. See ADR 0056.
	 *
	 * <p>Whose log is read comes from the token, never from the request, so a customer cannot name anyone
	 * else (ADR 0023). Not itself recorded: it is a customer reading their own.
	 */
	public List<CustomerEntry> mine(String authorization, int limit) {
		String customerId = caller.require(authorization);
		return jdbc.query(MINE, (rs, row) -> {
			String action = rs.getString("action");
			return new CustomerEntry(action, SAID_AS.getOrDefault(action, "A member of staff looked at your data"),
					rs.getString("resource_id"), rs.getTimestamp("read_at").toInstant());
		}, customerId, limit);
	}
}
