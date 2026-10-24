package com.flashcart.order.service;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The accounts closed by their holders, as this service has heard of them. See ADR 0053.
 *
 * <p>Learned from {@code AccountClosed}, so a checkout in the seconds between closing and hearing is not
 * refused. Recording is idempotent: a redelivered closure changes nothing.
 */
@Component
public class ClosedAccounts {

	private final JdbcTemplate jdbc;

	public ClosedAccounts(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
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
