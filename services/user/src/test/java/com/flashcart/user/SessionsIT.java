package com.flashcart.user;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.service.PasswordResets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Short access tokens, rotating refresh tokens, and signing out. See ADR 0059. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SessionsIT {

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
	private AccessTokens tokens;

	@Autowired
	private PasswordResets resets;

	@Autowired
	private ObjectMapper json;

	@Test
	@DisplayName("signing in gives a fifteen-minute access token and a thirty-day refresh token, kept only as a hash")
	void signingInStartsASession() {
		String email = register();

		Map<String, Object> session = signIn(email);

		assertThat(session).containsEntry("tokenType", "Bearer").containsEntry("expiresInSeconds", 900)
				.containsEntry("refreshExpiresInSeconds", 30 * 24 * 3600);
		JsonNode claims = claims((String) session.get("accessToken"));
		assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(900);
		String refresh = (String) session.get("refreshToken");
		assertThat(jdbc.queryForList("select token_hash from refresh_tokens", String.class))
				.doesNotContain(refresh).allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
	}

	@Test
	@DisplayName("a refresh token works once and comes back replaced; the same token twice ends the whole session")
	void refreshRotatesAndCatchesReuse() {
		Map<String, Object> first = signIn(register());
		String original = (String) first.get("refreshToken");

		ResponseEntity<Map> renewed = refresh(original);
		assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.OK);
		String next = (String) renewed.getBody().get("refreshToken");
		assertThat(next).isNotEqualTo(original);
		assertThat(tokens.subject((String) renewed.getBody().get("accessToken"))).isPresent();

		// The original, again. Its owner rotated it and would never send it twice, so this is a copy.
		assertThat(refresh(original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// And the copy's existence ends the session the owner holds too: they sign in again.
		assertThat(refresh(next).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("signing out ends this session and no other")
	void signingOutOfOneSession() {
		String email = register();
		String phone = (String) signIn(email).get("refreshToken");
		String laptop = (String) signIn(email).get("refreshToken");

		assertThat(rest.postForEntity("/api/v1/users/sessions/sign-out", Map.of("refreshToken", phone), Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(refresh(phone).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.OK);
		// An unknown token signs nothing out and says nothing.
		assertThat(rest.postForEntity("/api/v1/users/sessions/sign-out", Map.of("refreshToken", "nonsense"), Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	@DisplayName("signing out everywhere ends every session at its next refresh, this one included")
	void signingOutEverywhere() {
		String email = register();
		Map<String, Object> phone = signIn(email);
		String laptop = (String) signIn(email).get("refreshToken");

		assertThat(post("/api/v1/users/me/sign-out-everywhere", bearer((String) phone.get("accessToken"))))
				.isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(refresh((String) phone.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh(laptop).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// A fresh sign-in is unaffected.
		assertThat(refresh((String) signIn(email).get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(post("/api/v1/users/me/sign-out-everywhere", new HttpHeaders())).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a password reset ends every session, so whoever had the old password loses theirs")
	void aResetSignsOutEverywhere() {
		String email = register();
		String intruder = (String) signIn(email).get("refreshToken");

		resets.request(email);
		String resetId = jdbc.queryForObject("""
				select r.id::text from password_resets r join users u on u.id = r.user_id where u.email = ?""",
				String.class, email);
		String link = resets.mint(resetId).orElseThrow();
		resets.confirm(link.substring(link.indexOf("token=") + "token=".length()), "a-brand-new-password");

		assertThat(refresh(intruder).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("closing the account ends its sessions; disabling it stops the next refresh")
	void closedOrDisabledAccountsCannotRefresh() {
		String closing = register();
		Map<String, Object> session = signIn(closing);
		assertThat(rest.exchange("/api/v1/users/me/close", HttpMethod.POST, new HttpEntity<>(Map.of("password", PASSWORD),
				bearer((String) session.get("accessToken"))), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		// Revoked on the record, not only refused because the account is gone: the account check below would
		// refuse it either way, so it cannot be what shows the closure ended the session.
		String closedId = jdbc.queryForObject("select id::text from users where status = 'CLOSED' and email = "
				+ "'closed-' || id || '@closed.invalid' order by closed_at desc limit 1", String.class);
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?::uuid and revoked_at is null",
				Integer.class, closedId)).isZero();
		assertThat(refresh((String) session.get("refreshToken")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		// Disabled by hand, with nothing revoked: the refresh reads the account again and refuses.
		String disabled = register();
		String refresh = (String) signIn(disabled).get("refreshToken");
		jdbc.update("update users set status = 'DISABLED' where email = ?", disabled);
		assertThat(refresh(refresh).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a refresh reads the account again, so a granted or revoked role reaches the next token")
	void rolesFollowTheAccount() {
		String email = register();
		String refresh = (String) signIn(email).get("refreshToken");
		jdbc.update("update users set roles = 'SUPPORT' where email = ?", email);

		Map<String, Object> renewed = refresh(refresh).getBody();
		assertThat(tokens.hasAnyRole((String) renewed.get("accessToken"), AccessTokens.SUPPORT)).isTrue();

		jdbc.update("update users set roles = '' where email = ?", email);
		Map<String, Object> again = refresh((String) renewed.get("refreshToken")).getBody();
		assertThat(tokens.hasAnyRole((String) again.get("accessToken"), AccessTokens.SUPPORT)).isFalse();
	}

	@Test
	@DisplayName("an expired or made-up refresh token is refused like any other")
	void expiredAndUnknownTokens() {
		String email = register();
		String refresh = (String) signIn(email).get("refreshToken");
		jdbc.update("""
				update refresh_tokens set expires_at = now() - interval '1 second'
				 where user_id = (select id from users where email = ?)""", email);

		assertThat(refresh(refresh).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh("not-a-real-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private String register() {
		String email = "session-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		assertThat(rest.postForEntity("/api/v1/users", Map.of("email", email, "password", PASSWORD, "displayName", "S"),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return email;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> signIn(String email) {
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/users/sessions",
				Map.of("email", email, "password", PASSWORD), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private ResponseEntity<Map> refresh(String refreshToken) {
		return rest.postForEntity("/api/v1/users/sessions/refresh", Map.of("refreshToken", refreshToken), Map.class);
	}

	private HttpStatusCode post(String path, HttpHeaders headers) {
		return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), Map.class).getStatusCode();
	}

	private static HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private JsonNode claims(String jwt) {
		String payload = jwt.split("\\.")[1];
		return json.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
	}
}
