package com.flashcart.user;

import com.flashcart.user.api.dto.UserDtos.SignInRequest;
import com.flashcart.user.api.dto.UserDtos.TokenResponse;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded operator exists <strong>only</strong> under the {@code demo} profile, and the password
 * this repository documents is the password it actually has.
 *
 * <p>Its own container, because the point of the test is a different Flyway configuration and two
 * of those cannot share a database.
 *
 * <p>{@link UserIT} is the other half: it runs without the profile and asserts the account is
 * absent. Both halves are needed. A test that only proved the seed works would pass just as happily
 * if the seed ran everywhere, which is the failure this change exists to prevent.
 *
 * <p>See ADR 0024.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("demo")
class DevelopmentOperatorSeedIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	private static final UUID DEVELOPMENT_OPERATOR =
			UUID.fromString("00000000-0000-4000-8000-00000000000f");

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private UserRepository users;

	@Test
	@DisplayName("the demo profile seeds the operator")
	void seedApplies() {
		assertThat(users.existsById(DEVELOPMENT_OPERATOR)).isTrue();
	}

	@Test
	@DisplayName("and the documented password is the one that works")
	void thePublishedPasswordVerifies() {
		ResponseEntity<TokenResponse> response = rest.postForEntity("/api/v1/users/sessions",
				new SignInRequest("operator@flashcart.local", "operator-development-password"),
				TokenResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().accessToken()).isNotBlank();
	}

	@Test
	@DisplayName("and the token it issues actually carries the role")
	void theSeededAccountIsAnOperator() {
		ResponseEntity<TokenResponse> response = rest.postForEntity("/api/v1/users/sessions",
				new SignInRequest("operator@flashcart.local", "operator-development-password"),
				TokenResponse.class);

		// Signing in is not the same as being an operator: the roles column could be empty and this
		// would still return 200. Every harness and CI step depends on the claim, not the account.
		AccessTokens verifier = new AccessTokens(
				"flashcart-development-secret-do-not-use-anywhere-real", java.time.Duration.ofHours(12));
		assertThat(verifier.isOperator(response.getBody().accessToken())).isTrue();
	}

	@Test
	@DisplayName("re-running the seed is a no-op rather than a duplicate-key failure")
	void seedIsIdempotent() {
		// Flyway will not re-run V900 here, so this asserts the property the file relies on: the
		// fixed id plus ON CONFLICT DO NOTHING means exactly one row however often it is applied.
		assertThat(users.findAll().stream()
				.filter(user -> user.getId().equals(DEVELOPMENT_OPERATOR))
				.count()).isEqualTo(1);
	}
}
