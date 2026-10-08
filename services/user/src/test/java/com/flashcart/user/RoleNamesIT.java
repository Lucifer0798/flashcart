package com.flashcart.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A role can only be one that exists. See ADR 0047.
 *
 * <p>Roles are granted by writing {@code users.roles} directly, on purpose (ADR 0022). Before the
 * constraint this checks, a typo was stored, put in every token the account was issued, and matched no
 * rule anywhere -- the account just got 403s, with nothing pointing at why.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RoleNamesIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("every real role, alone or together, and none at all, is accepted")
	void knownRolesAreAccepted() {
		String id = register();
		for (String roles : List.of("", "WAREHOUSE", "CATALOG", "SUPPORT", "OPERATOR",
				"CATALOG,SUPPORT,WAREHOUSE", "OPERATOR,WAREHOUSE")) {
			assertThat(setRoles(id, roles)).as("roles '%s'", roles).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("a misspelt role is refused by the database, not stored and silently useless")
	void unknownRolesAreRefused() {
		String id = register();
		for (String roles : List.of("WAREHOSUE", "warehouse", "ADMIN", "WAREHOUSE,ADMIN",
				"WAREHOUSE,", ",WAREHOUSE", "WAREHOUSE, CATALOG", " WAREHOUSE")) {
			assertThatThrownBy(() -> setRoles(id, roles)).as("roles '%s'", roles)
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("ck_users_roles_known");
		}
	}

	@Test
	@DisplayName("a grant record must name a real role and give a reason")
	void grantRecordsAreChecked() {
		String id = register();
		assertThat(jdbc.update("insert into role_grants (user_id, role, action, reason, granted_by) "
				+ "values (?::uuid, 'WAREHOUSE', 'GRANT', 'joins the warehouse', 'test')", id)).isEqualTo(1);

		assertThatThrownBy(() -> jdbc.update("insert into role_grants (user_id, role, action, reason, granted_by) "
				+ "values (?::uuid, 'WAREHOUSE', 'GRANT', '   ', 'test')", id))
				.hasMessageContaining("ck_role_grants_reason");
		assertThatThrownBy(() -> jdbc.update("insert into role_grants (user_id, role, action, reason, granted_by) "
				+ "values (?::uuid, 'ADMIN', 'GRANT', 'why not', 'test')", id))
				.hasMessageContaining("ck_role_grants_role");
	}

	private int setRoles(String id, String roles) {
		return jdbc.update("update users set roles = ? where id = ?::uuid", roles, id);
	}

	@SuppressWarnings("unchecked")
	private String register() {
		Map<String, Object> created = rest.postForObject("/api/v1/users",
				Map.of("email", "staff-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
						"password", "a-sufficiently-long-password", "displayName", "Staff"),
				Map.class);
		return (String) created.get("id");
	}
}
