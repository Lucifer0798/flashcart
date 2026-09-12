package com.flashcart.user;

import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.repository.UserRepository;
import com.flashcart.user.api.dto.UserDtos.AddressRequest;
import com.flashcart.user.api.dto.UserDtos.RegisterRequest;
import com.flashcart.user.api.dto.UserDtos.SignInRequest;
import com.flashcart.user.api.dto.UserDtos.TokenResponse;
import com.flashcart.user.api.dto.UserDtos.UserResponse;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user service against a real PostgreSQL.
 *
 * <p>Two things here are worth more than the CRUD: that sign-in refuses to tell an attacker anything,
 * and that a token issued by this service says what the rest of the platform will read out of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private AccessTokens tokens;

	@Autowired
	private UserRepository users;

	private static String uniqueEmail() {
		return "shopper-" + UUID.randomUUID() + "@example.test";
	}

	private UserResponse register(String email) {
		ResponseEntity<UserResponse> response = rest.postForEntity("/api/v1/users",
				new RegisterRequest(email, "a-sufficiently-long-password", "Test Shopper"),
				UserResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	private String signIn(String email, String password) {
		return rest.postForObject("/api/v1/users/sessions", new SignInRequest(email, password),
				TokenResponse.class).accessToken();
	}

	private HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	// --- registration ---------------------------------------------------------------------------

	@Test
	@DisplayName("registering returns the account without ever echoing the password")
	void registerDoesNotEchoTheSecret() {
		String email = uniqueEmail();
		UserResponse user = register(email);

		assertThat(user.email()).isEqualTo(email.toLowerCase());
		assertThat(user.status()).isEqualTo("ACTIVE");
		assertThat(user.addresses()).isEmpty();

		// Asserted against the raw registration response, not a typed one: a typed record cannot
		// carry a field it does not declare, so deserialising first would make this assertion
		// impossible to fail. The point is that nobody adds a hash to the JSON later, and a hash in
		// an API response is a hash somebody eventually logs.
		String raw = rest.postForObject("/api/v1/users",
				new RegisterRequest(uniqueEmail(), "a-sufficiently-long-password", "Raw Body Check"),
				String.class);
		assertThat(raw).contains("\"email\"").doesNotContain("$2a$", "passwordHash", "password");
	}

	@Test
	@DisplayName("the same email cannot register twice, whatever its casing")
	void emailIsUniqueCaseInsensitively() {
		String email = uniqueEmail();
		register(email);

		ResponseEntity<Map> second = rest.postForEntity("/api/v1/users",
				new RegisterRequest(email.toUpperCase(), "another-long-enough-password", "Impostor"),
				Map.class);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody()).containsEntry("code", "EMAIL_TAKEN");
	}

	// --- signing in -----------------------------------------------------------------------------

	@Test
	@DisplayName("a token names the user the rest of the platform will bill")
	void tokenSubjectIsTheCustomerId() {
		String email = uniqueEmail();
		UserResponse user = register(email);

		String token = signIn(email, "a-sufficiently-long-password");

		// This is the contract with every other service: the subject IS the customerId that orders
		// are stored against. If this drifts, orders start being placed for a customer that does not
		// exist and nothing would notice until someone went looking for their order history.
		assertThat(tokens.subject(token)).contains(user.id().toString());
	}

	@Test
	@DisplayName("a wrong password and an unknown email are the same answer")
	void refusalRevealsNothing() {
		String email = uniqueEmail();
		register(email);

		ResponseEntity<Map> wrongPassword = rest.postForEntity("/api/v1/users/sessions",
				new SignInRequest(email, "not-the-right-password"), Map.class);
		ResponseEntity<Map> unknownEmail = rest.postForEntity("/api/v1/users/sessions",
				new SignInRequest(uniqueEmail(), "a-sufficiently-long-password"), Map.class);

		// Identical status and identical code. Anything that distinguishes them lets someone ask
		// "does this person shop here" and get an answer.
		assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(wrongPassword.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
		assertThat(unknownEmail.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
	}

	// --- /me, and who it belongs to ----------------------------------------------------------------

	@Test
	@DisplayName("/me answers for the token that asked, not for anyone named in the request")
	void meFollowsTheToken() {
		String emailA = uniqueEmail();
		String emailB = uniqueEmail();
		UserResponse a = register(emailA);
		register(emailB);

		String tokenA = signIn(emailA, "a-sufficiently-long-password");

		ResponseEntity<UserResponse> response = rest.exchange("/api/v1/users/me", HttpMethod.GET,
				new HttpEntity<>(bearer(tokenA)), UserResponse.class);

		assertThat(response.getBody().id()).isEqualTo(a.id());
		assertThat(response.getBody().email()).isEqualTo(emailA.toLowerCase());
	}

	@Test
	@DisplayName("no token, no account")
	void meRequiresAToken() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/users/me", Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a forged token is refused")
	void forgedTokensAreRefused() {
		String forged = new AccessTokens("a-completely-different-secret-that-is-long-enough",
				java.time.Duration.ofHours(1)).issue(UUID.randomUUID().toString(), "attacker@example.test");

		ResponseEntity<Map> response = rest.exchange("/api/v1/users/me", HttpMethod.GET,
				new HttpEntity<>(bearer(forged)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// --- addresses ------------------------------------------------------------------------------

	@Test
	@DisplayName("the first address becomes the default whether or not you asked")
	void firstAddressIsTheDefault() {
		String email = uniqueEmail();
		register(email);
		String token = signIn(email, "a-sufficiently-long-password");

		rest.exchange("/api/v1/users/me/addresses", HttpMethod.POST,
				new HttpEntity<>(new AddressRequest("Home", "1 Test Street", null, "London", "E1 6AN",
						"gb", false), bearer(token)),
				Map.class);

		UserResponse user = rest.exchange("/api/v1/users/me", HttpMethod.GET,
				new HttpEntity<>(bearer(token)), UserResponse.class).getBody();

		assertThat(user.addresses()).singleElement().satisfies(address -> {
			assertThat(address.isDefault()).isTrue();
			// Upper-cased on the way in, because the database checks the shape and a lower-case code
			// would be rejected by the constraint rather than by anything with a good error message.
			assertThat(address.country()).isEqualTo("GB");
		});
	}

	@Test
	@DisplayName("adding a second default demotes the first")
	void onlyOneDefaultSurvives() {
		String email = uniqueEmail();
		register(email);
		String token = signIn(email, "a-sufficiently-long-password");

		rest.exchange("/api/v1/users/me/addresses", HttpMethod.POST,
				new HttpEntity<>(new AddressRequest("Home", "1 Test Street", null, "London", "E1 6AN",
						"GB", true), bearer(token)), Map.class);
		rest.exchange("/api/v1/users/me/addresses", HttpMethod.POST,
				new HttpEntity<>(new AddressRequest("Work", "2 Other Road", null, "Leeds", "LS1 4AP",
						"GB", true), bearer(token)), Map.class);

		UserResponse user = rest.exchange("/api/v1/users/me", HttpMethod.GET,
				new HttpEntity<>(bearer(token)), UserResponse.class).getBody();

		// The partial unique index would reject a second default outright; the domain clears the old
		// one first so the constraint never has to fire. Both are doing their job here.
		assertThat(user.addresses()).hasSize(2);
		assertThat(user.addresses().stream().filter(a -> a.isDefault()).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("the service still reports itself live")
	void serviceInfo() {
		assertThat(rest.getForEntity("/api/v1/user/_info", Map.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("without the demo profile there is no operator account at all")
	void noSeededOperatorByDefault() {
		// The other half of DevelopmentOperatorSeedIT. That one proves the seed works; this one
		// proves it is not everywhere -- which is the property that actually matters, and the one a
		// passing seed test would happily hide.
		assertThat(users.existsById(UUID.fromString("00000000-0000-4000-8000-00000000000f"))).isFalse();
	}

	@Test
	@DisplayName("and the published development password opens nothing")
	void theDevelopmentOperatorCannotSignIn() {
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/users/sessions",
				new SignInRequest("operator@flashcart.local", "operator-development-password"),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
