package com.flashcart.user.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.user.domain.Address;
import com.flashcart.user.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the user API, kept together because they are small and paired. */
public final class UserDtos {

	private UserDtos() {
	}

	public record RegisterRequest(
			@NotBlank @Email @Size(max = 320) String email,
			// Length only. A composition rule ("one digit, one symbol") shrinks the search space it
			// claims to protect, and NIST stopped recommending them a decade ago.
			@NotBlank @Size(min = 12, max = 200) String password,
			@NotBlank @Size(max = 120) String displayName) {
	}

	public record SignInRequest(
			@NotBlank @Email String email,
			@NotBlank String password) {
	}

	/** Deliberately no refresh token: see the ADR. */
	public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
	}

	public record AddressRequest(
			@Size(max = 60) String label,
			@NotBlank @Size(max = 200) String line1,
			@Size(max = 200) String line2,
			@NotBlank @Size(max = 120) String city,
			@NotBlank @Size(max = 20) String postcode,
			@NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code")
			String country,
			boolean makeDefault) {
	}

	public record AddressResponse(UUID id, String label, String line1, String line2, String city,
			String postcode, String country, boolean isDefault) {

		public static AddressResponse of(Address a) {
			return new AddressResponse(a.getId(), a.getLabel(), a.getLine1(), a.getLine2(), a.getCity(),
					a.getPostcode(), a.getCountry(), a.isDefault());
		}
	}

	/** Note what is absent: the password hash, and any field a caller could use to impersonate. */
	public record UserResponse(UUID id, String email, String displayName, String status,
			Instant createdAt, List<AddressResponse> addresses) {

		public static UserResponse of(User user) {
			return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
					user.getStatus().name(), user.getCreatedAt(),
					user.getAddresses().stream().map(AddressResponse::of).toList());
		}
	}

	public record RenameRequest(@NotBlank @Size(max = 120) String displayName) {
	}
}
