package com.flashcart.user;

import java.util.Map;
import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.message.OrderConfirmed;
import com.flashcart.user.messaging.NoticeListener;
import com.flashcart.user.notice.NoticeSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A customer closing their own account. See ADR 0053. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AccountClosureIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	private static final String PASSWORD = "a-sufficiently-long-password";

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NoticeListener listener;

	@Autowired
	private NoticeSender sender;

	@Test
	@DisplayName("closing erases who the account was, keeps its id, and tells the other services")
	void closingErasesTheIdentity() {
		Shopper shopper = register();
		assertThat(rest.exchange("/api/v1/users/me/addresses", HttpMethod.POST, new HttpEntity<>(Map.of("label", "Home",
				"line1", "1 High Street", "city", "Leeds", "postcode", "LS1 1AA", "country", "GB", "makeDefault", true), shopper.headers()),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// There to be erased: an assertion that none are left proves nothing about an account that had none.
		assertThat(jdbc.queryForObject("select count(*) from addresses where user_id = ?::uuid", Integer.class,
				shopper.id())).isEqualTo(1);

		assertThat(close(shopper.headers(), PASSWORD)).isEqualTo(HttpStatus.NO_CONTENT);

		Map<String, Object> row = jdbc.queryForMap("select * from users where id = ?::uuid", shopper.id());
		assertThat(row).containsEntry("status", "CLOSED")
				.containsEntry("email", "closed-" + shopper.id() + "@closed.invalid")
				.containsEntry("display_name", "Closed account")
				.containsEntry("roles", "");
		assertThat(row.get("closed_at")).isNotNull();
		assertThat(row.get("password_hash")).isEqualTo("!closed");
		assertThat(jdbc.queryForObject("select count(*) from addresses where user_id = ?::uuid", Integer.class,
				shopper.id())).isZero();
		// Nothing anywhere still names them.
		assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, shopper.email()))
				.isZero();

		// Announced in the same transaction, to the topic every service can hear.
		assertThat(jdbc.queryForList("""
				select topic, message_key, payload->>'customerId' as customer from outbox_messages
				 where event_type = 'AccountClosed' and message_key = ?""", shopper.id()))
				.containsExactly(Map.of("topic", "flashcart.user.events", "message_key", shopper.id(),
						"customer", shopper.id()));
	}

	@Test
	@DisplayName("a wrong password closes nothing, so a token alone cannot destroy an account")
	void wrongPasswordClosesNothing() {
		Shopper shopper = register();

		ResponseEntity<Map> refused = rest.exchange("/api/v1/users/me/close", HttpMethod.POST,
				new HttpEntity<>(Map.of("password", "not-the-password"), shopper.headers()), Map.class);

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refused.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
		assertThat(jdbc.queryForObject("select status from users where id = ?::uuid", String.class, shopper.id()))
				.isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("select count(*) from outbox_messages where message_key = ?", Integer.class,
				shopper.id())).isZero();
		assertThat(close(shopper.headers(), "")).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("once closed, the old token reaches nothing, the password signs in to nothing, and the address is free")
	void afterClosing() {
		Shopper shopper = register();
		close(shopper.headers(), PASSWORD);

		assertThat(rest.exchange("/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(shopper.headers()), Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(close(shopper.headers(), PASSWORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(rest.postForEntity("/api/v1/users/sessions", Map.of("email", shopper.email(), "password", PASSWORD),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		// The same person can come back as a new account. It shares nothing with the old one.
		ResponseEntity<Map> again = rest.postForEntity("/api/v1/users",
				Map.of("email", shopper.email(), "password", PASSWORD, "displayName", "Back Again"), Map.class);
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(again.getBody().get("id")).isNotEqualTo(shopper.id());
	}

	@Test
	@DisplayName("a staff account can close, and its roles are revoked on the record rather than just vanishing")
	void staffRolesAreRevokedOnTheRecord() {
		Shopper staff = register();
		jdbc.update("update users set roles = 'SUPPORT,WAREHOUSE' where id = ?::uuid", staff.id());
		HttpHeaders signedIn = bearer(signIn(staff.email()));

		assertThat(close(signedIn, PASSWORD)).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(jdbc.queryForList("select role, action, reason, granted_by from role_grants where user_id = ?::uuid "
				+ "order by role", staff.id())).containsExactly(
						Map.of("role", "SUPPORT", "action", "REVOKE", "reason", "account closed by its holder",
								"granted_by", "account-closure"),
						Map.of("role", "WAREHOUSE", "action", "REVOKE", "reason", "account closed by its holder",
								"granted_by", "account-closure"));
	}

	@Test
	@DisplayName("what was emailed is deleted, and nothing new is kept or sent for a closed account")
	void emailsStop() {
		Shopper shopper = register();
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-CLOSE001", shopper.id()));
		assertThat(notices(shopper.id())).isEqualTo(1);

		close(shopper.headers(), PASSWORD);
		assertThat(notices(shopper.id())).isZero();

		// A refund or a dispatch announced afterwards is not even recorded.
		listener.onOrderConfirmed(new OrderConfirmed(meta(OrderConfirmed.TYPE), "FC-CLOSE002", shopper.id()));
		assertThat(notices(shopper.id())).isZero();

		// And one that slipped in regardless is skipped, never mailed to the placeholder address.
		jdbc.update("""
				insert into notices (notice_key, kind, customer_id, order_number, occurred_at, status)
				values ('order-confirmed:FC-CLOSE003', 'ORDER_CONFIRMED', ?, 'FC-CLOSE003', now(), 'PENDING')""",
				shopper.id());
		sender.sendDue();
		assertThat(jdbc.queryForMap("select status, skip_reason from notices where notice_key = 'order-confirmed:FC-CLOSE003'"))
				.containsEntry("status", "SKIPPED").containsEntry("skip_reason", "NO_RECIPIENT");
	}

	@Test
	@DisplayName("a closure typed by hand that leaves the email behind is refused by the database")
	void theDatabaseRefusesAHalfClosure() {
		Shopper shopper = register();

		assertThatThrownBy(() -> jdbc.update("update users set status = 'CLOSED', closed_at = now() where id = ?::uuid",
				shopper.id())).isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private record Shopper(String id, String email, HttpHeaders headers) {
	}

	private Shopper register() {
		String email = "closing-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		Map<String, Object> created = rest.postForObject("/api/v1/users",
				Map.of("email", email, "password", PASSWORD, "displayName", "Leaving Shopper"), Map.class);
		return new Shopper((String) created.get("id"), email, bearer(signIn(email)));
	}

	private String signIn(String email) {
		return (String) rest.postForObject("/api/v1/users/sessions", Map.of("email", email, "password", PASSWORD),
				Map.class).get("accessToken");
	}

	private static HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private HttpStatusCode close(HttpHeaders headers, String password) {
		return rest.exchange("/api/v1/users/me/close", HttpMethod.POST, new HttpEntity<>(Map.of("password", password),
				headers), Map.class).getStatusCode();
	}

	private int notices(String customerId) {
		return jdbc.queryForObject("select count(*) from notices where customer_id = ?", Integer.class, customerId);
	}

	private static EventMetadata meta(String type) {
		return EventMetadata.of(type, UUID.randomUUID());
	}
}
