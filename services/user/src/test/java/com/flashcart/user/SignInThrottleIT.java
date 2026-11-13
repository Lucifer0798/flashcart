package com.flashcart.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.user.service.AttemptThrottle;
import com.flashcart.user.service.PasswordResets;
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
 * Failed sign-ins are limited. See ADR 0058, and ADR 0072 for the credit a shared address earns.
 *
 * <p>Small limits, so each case takes a handful of requests rather than fifty. Clients are told apart by
 * {@code X-Forwarded-For}, as the gateway tells the service, because these requests all come from here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"flashcart.sign-in.limit.account-and-client.failures=3",
		"flashcart.sign-in.limit.client.failures=6",
		"flashcart.sign-in.limit.account.failures=9",
		// Small, so the cap is reached in a handful of sign-ins rather than three hundred. ADR 0072.
		"flashcart.sign-in.limit.client.credit-cap=4",
		"flashcart.password-reset.limit.client.requests=3",
		// Longer than every sign-in window, so pruning by the sign-in windows alone would be caught.
		"flashcart.password-reset.limit.client.window=PT2H" })
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
	private AttemptThrottle throttle;

	@Autowired
	private PasswordResets resets;

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

		List<String> keys = jdbc.queryForList("select key_hash from throttled_attempts", String.class);
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

		jdbc.update("update throttled_attempts set attempted_at = now() - interval '16 minutes'");
		assertThat(signIn(email, PASSWORD, here).getStatusCode()).isEqualTo(HttpStatus.OK);

		jdbc.update("update throttled_attempts set attempted_at = now() - interval '2 hours'");
		throttle.prune();
		assertThat(jdbc.queryForObject("select count(*) from throttled_attempts where attempted_at < now() - interval '1 hour'",
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

	// --- review fixes (ADR 0062) -----------------------------------------------------------------------

	@Test
	@DisplayName("a password reset lets the owner back in while an attack holds the account limit")
	void aResetLiftsTheAccountLockout() {
		String email = register();
		for (int i = 0; i < 9; i++) {
			signIn(email, "wrong-" + i, client());
		}
		String owner = client();
		assertThat(signIn(email, PASSWORD, owner).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

		resets.request(email, owner);
		String resetId = jdbc.queryForObject("""
				select r.id::text from password_resets r join users u on u.id = r.user_id where u.email = ?""",
				String.class, email);
		String link = resets.mint(resetId).orElseThrow();
		resets.confirm(link.substring(link.indexOf("token=") + "token=".length()), "a-brand-new-password", owner);

		// Before ADR 0062, still 429: the account limit counts by address, and the reset did not touch it.
		assertThat(signIn(email, "a-brand-new-password", owner).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(signIn(email, "a-brand-new-password", client()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("closing an account checks the password under the same throttle as sign-in")
	void closingIsThrottled() {
		String email = register();
		String here = client();
		HttpHeaders signedIn = new HttpHeaders();
		signedIn.setBearerAuth((String) signIn(email, PASSWORD, client()).getBody().get("accessToken"));
		signedIn.set("X-Forwarded-For", here);
		for (int i = 0; i < 3; i++) {
			assertThat(close(signedIn, "wrong-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		// Refused even with the right password, as sign-in is, and the account is still open.
		assertThat(close(signedIn, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(jdbc.queryForObject("select status from users where email = ?", String.class, email))
				.isEqualTo("ACTIVE");
		// The same counts: those failures hold sign-in back from here too, so neither path is a way round.
		assertThat(signIn(email, PASSWORD, here).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	// --- password reset requests (ADR 0060) -------------------------------------------------------------

	@Test
	@DisplayName("one client asking for resets for many addresses is stopped, whether or not they have accounts")
	void resetRequestsPerClient() {
		String asker = client();
		String known = register();
		for (int i = 0; i < 3; i++) {
			String address = i == 1 ? known : "stranger-" + UUID.randomUUID() + "@example.test";
			assertThat(requestReset(address, asker).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		}

		// The fourth is refused for an address with an account and one without, identically: the limit is the
		// client's, so the answer says nothing about the address.
		ResponseEntity<Map> forKnown = requestReset(known, asker);
		ResponseEntity<Map> forUnknown = requestReset("nobody-" + UUID.randomUUID() + "@example.test", asker);
		assertThat(forKnown.getStatusCode()).isEqualTo(forUnknown.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(forKnown.getBody().get("message")).isEqualTo(forUnknown.getBody().get("message"));
		assertThat(Long.parseLong(forKnown.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 7200L);

		// Another client asks as usual.
		assertThat(requestReset(known, client()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	}

	@Test
	@DisplayName("requests for addresses with no account count too, so the limit cannot be used to find real ones")
	void unknownAddressesCountToo() {
		String asker = client();
		for (int i = 0; i < 3; i++) {
			requestReset("nobody-" + UUID.randomUUID() + "@example.test", asker);
		}
		String known = register();

		assertThat(requestReset(known, asker).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		// Refused before the address was looked at: no reset was recorded for it.
		assertThat(jdbc.queryForObject("""
				select count(*) from password_resets r join users u on u.id = r.user_id where u.email = ?""",
				Integer.class, known)).isZero();
	}

	@Test
	@DisplayName("reset requests and failed sign-ins are counted apart, so neither uses up the other")
	void resetAndSignInAreSeparate() {
		String asker = client();
		String email = register();
		// Three failed sign-ins, then three resets: each half of its own limit, and together six -- the
		// sign-in client limit. Counted under one key, the resets would start refused, and the sign-in after
		// them would be.
		for (int i = 0; i < 3; i++) {
			signIn("stranger-" + UUID.randomUUID() + "@example.test", "wrong", asker);
		}
		for (int i = 0; i < 3; i++) {
			assertThat(requestReset("stranger-" + UUID.randomUUID() + "@example.test", asker).getStatusCode())
					.isEqualTo(HttpStatus.ACCEPTED);
		}

		assertThat(signIn(email, PASSWORD, asker).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("pruning keeps reset requests for as long as their own, longer window counts them")
	void pruningRespectsTheResetWindow() {
		String asker = client();
		for (int i = 0; i < 3; i++) {
			requestReset("stranger-" + UUID.randomUUID() + "@example.test", asker);
		}
		// Ninety minutes old: past every sign-in window, still inside the reset window of two hours.
		jdbc.update("update throttled_attempts set attempted_at = now() - interval '90 minutes' "
				+ "where attempted_at > now() - interval '1 minute'");
		throttle.prune();

		assertThat(requestReset("stranger-" + UUID.randomUUID() + "@example.test", asker).getStatusCode())
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	// --- many shoppers behind one address (ADR 0072) --------------------------------------------------

	@Test
	@DisplayName("an office's typos do not lock out its shoppers: each established account that got in buys one more failure")
	void aSharedAddressIsCreditedForItsShoppers() {
		String office = client();
		List<String> staff = List.of(established(), established(), established(), established());
		// Three accounts in: the limit of six now holds at nine.
		for (String email : staff.subList(0, 3)) {
			assertThat(signIn(email, PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		fail(office, 8);
		// Without the credit this would have been refused two failures ago.
		assertThat(signIn(staff.get(3), PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.OK);
		// Four in, so ten. The ninth failure still leaves room.
		fail(office, 1);
		assertThat(signIn(staff.get(0), PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.OK);
		// The tenth closes it, for everybody there, right passwords included.
		fail(office, 1);
		assertThat(signIn(staff.get(0), PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("accounts registered for the attack earn nothing: only accounts at least a day old count")
	void newAccountsEarnNoCredit() {
		String stuffer = client();
		Integer before = jdbc.queryForObject("select count(*) from sign_in_successes", Integer.class);
		for (int i = 0; i < 3; i++) {
			// Just registered, as an attacker's would be, and signed in from the attacking address.
			assertThat(signIn(register(), PASSWORD, stuffer).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		// Not a row for any of them.
		assertThat(jdbc.queryForObject("select count(*) from sign_in_successes", Integer.class)).isEqualTo(before);

		fail(stuffer, 6);

		assertThat(signIn(established(), PASSWORD, stuffer).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("the credit is per account, not per sign-in: one account signing in again and again buys one failure")
	void creditCountsAccountsNotSignIns() {
		String place = client();
		String regular = established();
		for (int i = 0; i < 4; i++) {
			assertThat(signIn(regular, PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		fail(place, 6);
		// One account, so seven: the sixth failure leaves room for one more.
		assertThat(signIn(regular, PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.OK);

		fail(place, 1);

		assertThat(signIn(regular, PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("the credit is capped, so accounts prepared a day ahead still buy only so many guesses")
	void creditIsCapped() {
		String place = client();
		List<String> accounts = List.of(established(), established(), established(), established(), established(),
				established());
		for (String email : accounts) {
			assertThat(signIn(email, PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		// Six accounts in, but the cap is four: ten, not twelve.
		fail(place, 9);
		assertThat(signIn(accounts.get(0), PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.OK);

		fail(place, 1);

		assertThat(signIn(accounts.get(0), PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("only sign-ins from the same address count, so accounts that got in elsewhere earn a stuffer nothing")
	void creditIsPerAddress() {
		String stuffer = client();
		String elsewhere = client();
		for (int i = 0; i < 3; i++) {
			assertThat(signIn(established(), PASSWORD, elsewhere).getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		fail(stuffer, 6);

		assertThat(signIn(established(), PASSWORD, stuffer).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("the credit does not loosen the tight limit: one account guessed from a busy office is still stopped")
	void creditLeavesThePairLimitAlone() {
		String office = client();
		for (int i = 0; i < 4; i++) {
			assertThat(signIn(established(), PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		String target = established();

		for (int i = 0; i < 3; i++) {
			assertThat(signIn(target, "a-wrong-password", office).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		assertThat(signIn(target, PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		// The office as a whole is fine: three failures against a limit of ten.
		assertThat(signIn(established(), PASSWORD, office).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("credit ages out with the window and is pruned, and like the failures it is kept only as hashes")
	void creditAgesOutAndIsHashed() {
		String place = client();
		String email = established();
		assertThat(signIn(email, PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.OK);

		List<Map<String, Object>> rows = jdbc.queryForList("select client_hash, pair_hash from sign_in_successes");
		assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(row.values()).allSatisfy(value -> {
			assertThat((String) value).matches("[0-9a-f]{64}").doesNotContain(place).doesNotContain(email);
		}));

		jdbc.update("update sign_in_successes set succeeded_at = now() - interval '16 minutes'");
		fail(place, 6);
		assertThat(signIn(email, PASSWORD, place).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

		throttle.prune();
		assertThat(jdbc.queryForObject("select count(*) from sign_in_successes where succeeded_at < now() - interval '15 minutes'",
				Integer.class)).isZero();
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

	/** An account registered two days ago, as most shoppers' are by the time a sale opens. */
	private String established() {
		String email = register();
		jdbc.update("update users set created_at = now() - interval '2 days' where email = ?", email);
		return email;
	}

	/** That many failed sign-ins from this client, each for a different address, as stuffing or typos look. */
	private void fail(String clientAddress, int times) {
		for (int i = 0; i < times; i++) {
			assertThat(signIn("typo-" + UUID.randomUUID() + "@example.test", "password123456", clientAddress)
					.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	private ResponseEntity<Map> close(HttpHeaders headers, String password) {
		return rest.exchange("/api/v1/users/me/close", HttpMethod.POST, new HttpEntity<>(Map.of("password", password),
				headers), Map.class);
	}

	private ResponseEntity<Map> requestReset(String email, String forwardedFor) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Forwarded-For", forwardedFor);
		return rest.exchange("/api/v1/users/password-reset", HttpMethod.POST, new HttpEntity<>(Map.of("email", email),
				headers), Map.class);
	}

	private ResponseEntity<Map> signIn(String email, String password, String forwardedFor) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Forwarded-For", forwardedFor);
		return rest.exchange("/api/v1/users/sessions", HttpMethod.POST,
				new HttpEntity<>(Map.of("email", email, "password", password), headers), Map.class);
	}
}
