package com.flashcart.user.api;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.api.dto.UserDtos.AddressRequest;
import com.flashcart.user.api.dto.UserDtos.AddressResponse;
import com.flashcart.user.api.dto.UserDtos.RegisterRequest;
import com.flashcart.user.api.dto.UserDtos.RenameRequest;
import com.flashcart.user.api.dto.UserDtos.SignInRequest;
import com.flashcart.user.api.dto.UserDtos.TokenResponse;
import com.flashcart.user.api.dto.UserDtos.UserResponse;
import com.flashcart.user.domain.Address;
import com.flashcart.user.domain.User;
import com.flashcart.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts, sign-in and addresses.
 *
 * <p>This service is the only one that mints tokens and the only one that stores a password. It does
 * not guard anything itself — the gateway turns unauthenticated traffic away at the edge, and the
 * order service verifies the token again before believing who is buying.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService users;
	private final AccessTokens tokens;
	private final Duration ttl;

	public UserController(UserService users, AccessTokens tokens,
			@Value("${flashcart.security.jwt.ttl:PT12H}") Duration ttl) {
		this.users = users;
		this.tokens = tokens;
		this.ttl = ttl;
	}

	@PostMapping
	@Operation(summary = "Register an account")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Registered"),
			@ApiResponse(responseCode = "409", description = "EMAIL_TAKEN")
	})
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		User user = users.register(request.email(), request.password(), request.displayName());
		return ResponseEntity.created(URI.create("/api/v1/users/" + user.getId()))
				.body(UserResponse.of(user));
	}

	@PostMapping("/sessions")
	@Operation(summary = "Sign in and receive an access token",
			description = "A wrong password, an unknown email and a disabled account are all 401 "
					+ "INVALID_CREDENTIALS. Telling them apart would let anyone enumerate accounts.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Signed in"),
			@ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS")
	})
	public TokenResponse signIn(@Valid @RequestBody SignInRequest request) {
		String token = users.signIn(request.email(), request.password());
		return new TokenResponse(token, "Bearer", ttl.toSeconds());
	}

	@GetMapping("/me")
	@Operation(summary = "The signed-in account")
	public UserResponse me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return UserResponse.of(users.require(caller(authorization)));
	}

	@PatchMapping("/me")
	@Operation(summary = "Change the display name")
	public UserResponse rename(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody RenameRequest request) {
		return UserResponse.of(users.rename(caller(authorization), request.displayName()));
	}

	@PostMapping("/me/addresses")
	@Operation(summary = "Add an address",
			description = "The first address becomes the default whether or not you ask for it.")
	public ResponseEntity<AddressResponse> addAddress(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody AddressRequest request) {

		Address address = users.addAddress(caller(authorization), Address.of(request.label(),
				request.line1(), request.line2(), request.city(), request.postcode(),
				request.country(), request.makeDefault()));
		return ResponseEntity.status(201).body(AddressResponse.of(address));
	}

	/**
	 * Who is asking, according to their own token.
	 *
	 * <p>Note there is no path variable anywhere in this controller: everything operates on
	 * {@code /me}. A {@code PATCH /users/{id}} would need an authorisation check on every call, and
	 * the check that is never written is the one that is never wrong.
	 */
	private UUID caller(String authorization) {
		return AccessTokens.bearer(authorization)
				.flatMap(tokens::subject)
				.map(UUID::fromString)
				.orElseThrow(() -> new UnauthenticatedException("A valid access token is required"));
	}
}
