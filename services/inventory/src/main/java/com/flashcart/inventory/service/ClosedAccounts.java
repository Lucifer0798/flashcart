package com.flashcart.inventory.service;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The accounts closed by their holders, as this service has heard of them. See ADR 0054.
 *
 * <p>Joining a waitlist and closing an account take the same per-customer lock, so the two cannot
 * interleave. Without it, a join that read "not closed" before the closure committed could insert a
 * place after the closure had already cancelled that customer's places. That place would then be told,
 * hold a unit, and never be cleaned up.
 */
@Component
public class ClosedAccounts {

	private final JdbcTemplate jdbc;

	public ClosedAccounts(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Held until the caller's transaction ends. Per customer, so nobody else's join waits on it. */
	public void lock(String customerId) {
		jdbc.query("select pg_advisory_xact_lock(hashtext('closed-account:' || ?))", rs -> { }, customerId);
	}

	public void record(String customerId, Instant closedAt) {
		jdbc.update("insert into closed_accounts (customer_id, closed_at) values (?, ?) on conflict do nothing",
				customerId, Timestamp.from(closedAt));
	}

	public boolean isClosed(String customerId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from closed_accounts where customer_id = ?)", Boolean.class, customerId));
	}
}
