package com.flashcart.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.user.service.SignInThrottle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failed sign-ins are limited. See ADR 0058.
 *
 * <p>Small limits, so each case takes a handful of requests rather than fifty. Clients are told apart by
 * {@code X-Forwarded-For}, as the gateway tells the service, because these requests all come from here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"flashcart.sign-in.limit.account-and-client.failures=3",
		"flashcart.sign-in.limit.client.failures=6",
		"flashcart.sign-in.limit.account.failures=9" })
@AutoConfigureTestRestTemplate
class SignInThrottleIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	private static final String PASSWORD = "the-right-password";

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private SignInThrottle throttle;

	@Test
	@DisplayName("one client guessing one account is stopped, even when it then guesses right; the owner elsewhere is not")
	void oneAccountFromOnePlace() {
		String email = register();
		String guesser = client();
		for (int i = 0; i < 3; i++) {
			assertThat(signIn(email, "wrong-" + i, guesser).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		ResponseEntity<Map> refused = signIn(email, PASSWORD, guesser);
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(refused.getBody()).containsEntry("code", "TOO_MANY_ATTEMPTS");
		long retryAfter = Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		assertThat(retryAfter).isBetween(1L, 15 * 60L);

		// The owner, somewhere else, signs in as usual.
		assertThat(signIn(email, PASSWORD, client()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a success clears this client's count for the account, so a mistyped password does not linger")
	void successClearsTheSlate() {
		String email = register();
		String here = client();
		signIn(email, "typo-1", here);
		signIn(email, "typo-2", here);
		assertThat(signIn(email, PASSWORD, here).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Two more would make four, past the limit of three, had the success not cleared the first two.
		signIn(email, "typo-3", here);
		signIn(email, "typo-4", here);
		assertThat(signIn(email, PASSWORD, here).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("one client trying many accounts once each is stopped, though no single account was tried twice")
	void credentialStuffing() {
		String stuffer = client();
		for (int i = 0; i < 6; i++) {
			assertThat(signIn("victim-" + UUID.randomUUID() + "@example.test", "password123456", stuffer)
					.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		String email = register();

		assertThat(signIn(email, PASSWORD, stuffer).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		// Only that client.
		assertThat(signIn(email, PASSWORD, client()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("one account guessed from many places is stopped everywhere, but a password reset still works")
	void oneAccountFromManyPlaces() {
		String email = register();
		for (int i = 0; i < 9; i++) {
			assertThat(signIn(email, "wrong-" + i, client()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		assertThat(signIn(email, PASSWORD, client()).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		// The way back in for the owner while this holds.
		assertThat(rest.postForEntity("/api/v1/users/password-reset", Map.of("email", email), Map.class)
				.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	}

	@Test
	@DisplayName("an address with no account is throttled exactly as one with an account, so neither is told apart")
	void unknownAddressesLookTheSame() {
		String known = register();
		String unknown = "nobody-" + UUID.randomUUID() + "@example.test";
		String a = client();
		String b = client();
		for (int i = 0; i < 3; i++) {
			signIn(known, "wrong-" + i, a);
			signIn(unknown, "wrong-" + i, b);
		}

		ResponseEntity<Map> knownRefusal = signIn(known, "wrong", a);
		ResponseEntity<Map> unknownRefusal = signIn(unknown, "wrong", b);
		assertThat(unknownRefusal.getStatusCode()).isEqualTo(knownRefusal.getStatusCode())
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(unknownRefusal.getBody().get("message")).isEqualTo(knownRefusal.getBody().get("message"));
	}

	@Test
	@DisplayName("the table holds hashes, not the addresses people typed or where they typed them from")
	void storedAsHashes() {
		String email = "hashed-" + UUID.randomUUID() + "@example.test";
		String from = client();
		signIn(email, "wrong", from);

		List<String> keys = jdbc.queryForList("select key_hash from sign_in_failures", String.class);
		assertThat(keys).isNotEmpty().allSatisfy(key -> assertThat(key).matches("[0-9a-f]{64}"));
		assertThat(String.join(",", keys)).doesNotContain(email).doesNotContain(from);
	}

	@Test
	@DisplayName("failures age out of the window, and are pruned once they can no longer count")
	void failuresAgeOut() {
		String email = register();
		String here = client();
		for (int i = 0; i < 3; i++) {
			signIn(email, "wrong-" + i, here);
		}
		assertThat(signIn(email, PASSWORD, here).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

		jdbc.update("update sign_in_failures set failed_at = now() - interval '16 minutes'");
		assertThat(signIn(email, PASSWORD, here).getStatusCode()).isEqualTo(HttpStatus.OK);

		jdbc.update("update sign_in_failures set failed_at = now() - interval '2 hours'");
		throttle.prune();
		assertThat(jdbc.queryForObject("select count(*) from sign_in_failures where failed_at < now() - interval '1 hour'",
				Integer.class)).isZero();
	}

	@Test
	@DisplayName("only the right-most forwarded address counts, so a guesser cannot pick a fresh one for each try")
	void forgedForwardedAddressesDoNotHelp() {
		String email = register();
		String real = client();
		for (int i = 0; i < 3; i++) {
			signIn(email, "wrong-" + i, "10.9." + i + ".1, " + real);
		}

		// Whatever the client writes to the left, the gateway's entry on the right is what counts.
		assertThat(signIn(email, PASSWORD, "10.9.9.9, " + real).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private static String client() {
		UUID id = UUID.randomUUID();
		return "203.0." + (id.getMostSignificantBits() & 0xff) + "." + (id.getLeastSignificantBits() & 0xff) + "-"
				+ id.toString().substring(0, 6);
	}

	private String register() {
		String email = "throttled-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		assertThat(rest.postForEntity("/api/v1/users", Map.of("email", email, "password", PASSWORD, "displayName", "T"),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return email;
	}

	private ResponseEntity<Map> signIn(String email, String password, String forwardedFor) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Forwarded-For", forwardedFor);
		return rest.exchange("/api/v1/users/sessions", HttpMethod.POST,
				new HttpEntity<>(Map.of("email", email, "password", password), headers), Map.class);
	}
}
