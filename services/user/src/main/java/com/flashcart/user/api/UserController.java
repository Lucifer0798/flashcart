package com.flashcart.user.api;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.api.dto.UserDtos.AddressRequest;
import com.flashcart.user.api.dto.UserDtos.AddressResponse;
import com.flashcart.user.api.dto.UserDtos.CloseRequest;
import com.flashcart.user.api.dto.UserDtos.PasswordResetConfirmation;
import com.flashcart.user.api.dto.UserDtos.PasswordResetRequest;
import com.flashcart.user.api.dto.UserDtos.RegisterRequest;
import com.flashcart.user.api.dto.UserDtos.RenameRequest;
import com.flashcart.user.api.dto.UserDtos.SignInRequest;
import com.flashcart.user.api.dto.UserDtos.TokenResponse;
import com.flashcart.user.api.dto.UserDtos.UserResponse;
import com.flashcart.user.domain.Address;
import com.flashcart.user.domain.User;
import com.flashcart.user.service.PasswordResets;
import com.flashcart.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
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
	private final PasswordResets resets;
	private final AccessTokens tokens;
	private final Duration ttl;

	public UserController(UserService users, PasswordResets resets, AccessTokens tokens,
			@Value("${flashcart.security.jwt.ttl:PT12H}") Duration ttl) {
		this.users = users;
		this.resets = resets;
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
			@ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS"),
			@ApiResponse(responseCode = "429", description = "TOO_MANY_ATTEMPTS -- too many recent failures, "
					+ "for this account from here, from here at all, or for this account from anywhere. "
					+ "Retry-After says when. Refused even with the right password. See ADR 0058.")
	})
	public TokenResponse signIn(@Valid @RequestBody SignInRequest request, HttpServletRequest http) {
		String token = users.signIn(request.email(), request.password(), clientAddress(http));
		return new TokenResponse(token, "Bearer", ttl.toSeconds());
	}

	@PostMapping("/password-reset")
	@Operation(summary = "Ask for a password reset email",
			description = "Always 202, and the same body, whether or not the address has an account: telling "
					+ "them apart would let anyone ask whether somebody shops here. An active account is sent "
					+ "a link that works once, within 30 minutes. Asking again within a minute sends nothing "
					+ "more. See ADR 0057.")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "If the address has an account, an email is on its way")
	})
	public ResponseEntity<Map<String, String>> requestReset(@Valid @RequestBody PasswordResetRequest request) {
		resets.request(request.email());
		return ResponseEntity.accepted().body(Map.of("message",
				"If that address has an account, we have emailed it a link to reset the password."));
	}

	@PostMapping("/password-reset/confirm")
	@Operation(summary = "Set a new password with the token from a reset email",
			description = "The token works once and expires 30 minutes after the request. Using it cancels the "
					+ "account's other outstanding reset links. Tokens already issued for signing in are not "
					+ "revoked, and stay valid until they expire. See ADR 0057.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Password changed"),
			@ApiResponse(responseCode = "400", description = "RESET_TOKEN_INVALID -- unknown, expired or already "
					+ "used, deliberately not told apart; or the new password is too short")
	})
	public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmation request) {
		resets.confirm(request.token(), request.newPassword());
		return ResponseEntity.noContent().build();
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
			description = "makeDefault may be left out, and then means false. The first address becomes "
					+ "the default whether or not you ask for it.")
	public ResponseEntity<AddressResponse> addAddress(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody AddressRequest request) {

		Address address = users.addAddress(caller(authorization), Address.of(request.label(),
				request.line1(), request.line2(), request.city(), request.postcode(),
				request.country(), Boolean.TRUE.equals(request.makeDefault())));
		return ResponseEntity.status(201).body(AddressResponse.of(address));
	}

	@PostMapping("/me/close")
	@Operation(summary = "Close the signed-in account",
			description = "Irreversible. Needs the current password as well as the token. Erases the email, "
					+ "name, password and addresses, and the emails kept about the account; orders, payments "
					+ "and parcels are kept as records, under an id that no longer leads to anyone. New "
					+ "checkouts from the account are refused. The email address can be registered again. "
					+ "See ADR 0053.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Closed"),
			@ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS -- wrong password; or the account "
					+ "is already closed")
	})
	public ResponseEntity<Void> close(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody CloseRequest request) {
		users.close(caller(authorization), request.password());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Where a sign-in came from, for the throttle (ADR 0058).
	 *
	 * <p>Through the gateway, the right-most {@code X-Forwarded-For} entry: the address the gateway itself
	 * saw, appended after anything the client sent. Entries to its left are whatever the client chose to
	 * write, and trusting them would let a guesser pick a fresh address for every attempt. Straight to this
	 * service's published port there is no gateway, and that last entry is also the client's to write --
	 * the same exposure every service's published port has, accepted in ADR 0049 for this stack.
	 */
	static String clientAddress(HttpServletRequest http) {
		String forwarded = http.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			String[] hops = forwarded.split(",");
			String last = hops[hops.length - 1].trim();
			if (!last.isEmpty()) {
				return last;
			}
		}
		return http.getRemoteAddr();
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
