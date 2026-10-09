package com.flashcart.user;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 moves the back-in-stock queue into the general notices table (ADR 0048). A migration that moves
 * rows is tested on rows: migrate to V5, write notices in each state the old table allowed, migrate on,
 * and read what arrived.
 *
 * <p>No Spring context: this drives Flyway directly against a database of its own, so the application's
 * own migrate-on-start cannot run V6 before the old rows exist.
 */
class NoticesMigrationIT {

	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Test
	@DisplayName("every back-in-stock notice moves across with its state, its attempts and its hold")
	void backInStockNoticesMoveAcross() throws Exception {
		String database = "migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()); Statement create = admin.createStatement()) {
			create.execute("create database " + database);
		}
		String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));

		flyway(url, "5").migrate();
		UUID pending = UUID.randomUUID();
		UUID lapsed = UUID.randomUUID();
		UUID sent = UUID.randomUUID();
		jdbc.update("""
				insert into back_in_stock_notices
				       (waitlist_entry_id, customer_id, sku, notified_at, held_until, status, skip_reason, attempts, sent_at)
				values (?, 'cust-1', 'AUD-1', now(), timestamptz '2099-01-01 12:34:56.789+00', 'PENDING', null, 2, null),
				       (?, 'cust-2', 'AUD-2', now(), now() - interval '1 hour', 'SKIPPED', 'HOLD_LAPSED', 0, null),
				       (?, 'cust-3', 'AUD-3', now(), null, 'SENT', null, 1, now())""", pending, lapsed, sent);

		flyway(url, "6").migrate();

		Map<String, Object> moved = jdbc.queryForMap(
				"select kind, status, attempts, payload::text as payload, send_before from notices where notice_key = ?",
				"back-in-stock:" + pending);
		assertThat(moved).containsEntry("kind", "BACK_IN_STOCK").containsEntry("status", "PENDING")
				.containsEntry("attempts", 2);
		// The hold now travels in the payload, and in a form the email can read back.
		String held = jdbc.queryForObject("select payload->>'heldUntil' from notices where notice_key = ?",
				String.class, "back-in-stock:" + pending);
		assertThat(Instant.parse(held)).isEqualTo(Instant.parse("2099-01-01T12:34:56.789Z"));

		assertThat(jdbc.queryForMap("select status, skip_reason from notices where notice_key = ?", "back-in-stock:" + lapsed))
				.containsEntry("status", "SKIPPED").containsEntry("skip_reason", "STALE");
		assertThat(jdbc.queryForObject("select payload->>'heldUntil' is not null from notices where notice_key = ?", Boolean.class,
				"back-in-stock:" + sent)).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from notices", Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select to_regclass('back_in_stock_notices') is null", Boolean.class)).isTrue();
	}

	private static Flyway flyway(String url, String target) {
		return Flyway.configure()
				.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target(target)
				.load();
	}
}
