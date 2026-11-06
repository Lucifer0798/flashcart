package com.flashcart.user.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.user.notice.NoticeHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What the user service holds about an account beyond its profile, as the customer sees it. See ADR 0066.
 *
 * <p>What is left out is as deliberate as what is in. No password hash, and no hash of any refresh or reset
 * token: they are credentials, and handing one back would only make a copy of it. No mail-server errors or
 * retry counts on the emails: those are about the platform's mail, not the customer, which is why support's
 * view has them (ADR 0051) and this does not.
 */
@Component
public class AccountExport {

	/** More than ninety days of emails can hold, since they are deleted after that (ADR 0052). */
	private static final int ALL_EMAILS = 10_000;

	private final JdbcTemplate jdbc;
	private final NoticeHistory notices;

	public AccountExport(JdbcTemplate jdbc, NoticeHistory notices) {
		this.jdbc = jdbc;
		this.notices = notices;
	}

	/** Every email the platform has kept a record of for this account: what it said, and whether it went. */
	public List<Map<String, Object>> emails(UUID userId) {
		return notices.forCustomer(userId.toString(), null, ALL_EMAILS).stream().map(email -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("subject", email.subject());
			entry.put("body", email.body());
			entry.put("orderNumber", email.orderNumber());
			entry.put("status", email.status());
			entry.put("occurredAt", email.occurredAt());
			entry.put("sentAt", email.sentAt());
			return entry;
		}).toList();
	}

	/** Sessions, as when each token was issued and what became of it. Never the token, or its hash. */
	public List<Map<String, Object>> sessions(UUID userId) {
		return jdbc.query("""
				select family_id, issued_at, expires_at, used_at, revoked_at
				  from refresh_tokens where user_id = ? order by issued_at desc""", (rs, row) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("session", rs.getObject("family_id", UUID.class));
			entry.put("issuedAt", instant(rs, "issued_at"));
			entry.put("expiresAt", instant(rs, "expires_at"));
			entry.put("usedAt", instant(rs, "used_at"));
			entry.put("revokedAt", instant(rs, "revoked_at"));
			return entry;
		}, userId);
	}

	/** Password resets asked for, and whether they were used. Never the token, or its hash. */
	public List<Map<String, Object>> passwordResets(UUID userId) {
		return jdbc.query("""
				select requested_at, expires_at, used_at
				  from password_resets where user_id = ? order by requested_at desc""", (rs, row) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("requestedAt", instant(rs, "requested_at"));
			entry.put("expiresAt", instant(rs, "expires_at"));
			entry.put("usedAt", instant(rs, "used_at"));
			return entry;
		}, userId);
	}

	/**
	 * Staff roles this account was granted or lost, for an account that ever had one. Not who changed it, nor
	 * the reason they gave, which is written by staff for staff and can name people: as with reads, that it
	 * happened and when, not who (ADR 0056).
	 */
	public List<Map<String, Object>> roleChanges(UUID userId) {
		return jdbc.query("""
				select role, action, granted_at
				  from role_grants where user_id = ? order by granted_at desc""", (rs, row) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("role", rs.getString("role"));
			entry.put("action", rs.getString("action"));
			entry.put("at", instant(rs, "granted_at"));
			return entry;
		}, userId);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}
