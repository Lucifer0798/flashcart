package com.flashcart.user.service;

import java.util.UUID;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.domain.Address;
import com.flashcart.user.domain.User;
import com.flashcart.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, sign-in and addresses. */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	private final UserRepository users;
	private final PasswordEncoder passwords;
	private final AccessTokens tokens;

	public UserService(UserRepository users, PasswordEncoder passwords, AccessTokens tokens) {
		this.users = users;
		this.passwords = passwords;
		this.tokens = tokens;
	}

	@Transactional
	public User register(String email, String rawPassword, String displayName) {
		String normalised = email.trim().toLowerCase();
		if (users.existsByEmail(normalised)) {
			throw new ConflictException("EMAIL_TAKEN", "An account already exists for that email");
		}
		try {
			return users.save(User.register(normalised, passwords.encode(rawPassword), displayName));
		}
		catch (DataIntegrityViolationException ex) {
			// The existsByEmail check above is a courtesy, not the guarantee. Two registrations for
			// the same address racing each other both pass it; the unique constraint is what actually
			// decides, and the loser gets the same answer as if it had arrived second.
			throw new ConflictException("EMAIL_TAKEN", "An account already exists for that email");
		}
	}

	/**
	 * Signs in, or refuses without saying why.
	 *
	 * <p>An unknown email and a wrong password produce the identical response, and both run the
	 * password encoder. Returning faster for an unknown address is an account-enumeration oracle:
	 * anyone can then ask "does this person shop here" and get an answer from the timing alone.
	 */
	@Transactional(readOnly = true)
	public String signIn(String email, String rawPassword) {
		User user = users.findByEmail(email.trim().toLowerCase()).orElse(null);

		String hash = user != null ? user.getPasswordHash()
				// A real BCrypt hash of a value nobody knows, so the comparison costs what a real one
				// costs. Skipping it here is what makes the timing side-channel.
				: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

		boolean matches = passwords.matches(rawPassword, hash);
		if (user == null || !matches || user.getStatus() != User.Status.ACTIVE) {
			log.info("Rejected a sign-in attempt for {}", email);
			throw new UnauthenticatedException();
		}

		return tokens.issue(user.getId().toString(), user.getEmail(), user.getRoles());
	}

	@Transactional(readOnly = true)
	public User require(UUID id) {
		return users.findWithAddresses(id)
				.orElseThrow(() -> ResourceNotFoundException.of("User", id.toString()));
	}

	@Transactional
	public Address addAddress(UUID userId, Address address) {
		User user = require(userId);
		if (address.isDefault()) {
			// Demote first and force it to the database before inserting. See User.clearDefaultAddress
			// for why the order matters and why the flush is not optional.
			user.clearDefaultAddress();
			users.flush();
		}
		return user.addAddress(address);
	}

	@Transactional
	public User rename(UUID userId, String displayName) {
		User user = require(userId);
		user.rename(displayName);
		return user;
	}
}
