package com.flashcart.common.security;

import com.flashcart.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records an operator reading data that is not their own.
 *
 * <p>ADR 0023 opened the customer read paths and left this behind: one account can read every
 * customer's payment history, and nothing recorded that it happened. Both records that named it said
 * the same thing — obvious to add now, awkward to add later. See ADR 0025.
 *
 * <h2>What is recorded, and what is not</h2>
 *
 * <p>Only an operator reading <strong>somebody else's</strong> row. A customer reading their own is
 * the ordinary path and auditing it would bury the signal in exactly the traffic nobody needs to
 * review; an operator reading their own payment is also just a customer reading their own.
 *
 * <p>The row says who looked, whose data it was, what they looked at and when. It does not copy the
 * data. An audit table that duplicates the payments table is a second place for the same customer
 * information to leak from, and it answers no question the first one cannot.
 *
 * <h2>Why a failure here fails the read</h2>
 *
 * <p>The usual objection to synchronous auditing is that it makes a logging concern able to break a
 * working feature. That objection does not apply here, because this writes to <em>the same database
 * the read has just come out of</em>. There is no new dependency to fail: if this insert cannot
 * happen, the query that produced the data could not have happened either.
 *
 * <p>So the exception is allowed to propagate, and the effect is the property worth having — an
 * operator never sees another customer's data without a record of it existing. Swallowing it would
 * turn the one failure mode that matters, "the audit table is gone", into the one state nobody
 * notices.
 *
 * <p>The exception is a read-only replica, where selects succeed and inserts do not. This platform
 * has none; a deployment that adds one has to decide whether unrecorded reads or refused reads are
 * worse, and that is a decision, not an oversight.
 */
public class OperatorAccessLog {

	private static final Logger log = LoggerFactory.getLogger(OperatorAccessLog.class);

	private static final String INSERT = """
			insert into operator_access_log
			    (operator_id, action, resource_id, customer_id, correlation_id, read_at)
			values (?, ?, ?, ?, ?, now())""";

	private final JdbcTemplate jdbc;

	public OperatorAccessLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param operatorId the token subject of the operator who asked
	 * @param action     what they did, e.g. {@code READ_PAYMENT}
	 * @param resourceId which row — an id, order number or tracking number; null for a listing
	 * @param customerId whose data it was
	 */
	public void record(String operatorId, String action, String resourceId, String customerId) {
		jdbc.update(INSERT, operatorId, action, resourceId, customerId, CorrelationId.current());
		// Also logged, because the table answers "who read this customer's data" and the log answers
		// "what happened in this request" -- and during an incident the second one is usually what
		// somebody has open.
		log.info("Operator {} read {} {} belonging to {}", operatorId, action, resourceId, customerId);
	}
}
