package com.flashcart.user.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.AccountClosed;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.domain.Address;
import com.flashcart.user.domain.User;
import com.flashcart.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, sign-in, addresses, and closing the account. */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	private final UserRepository users;
	private final PasswordEncoder passwords;
	private final AccessTokens tokens;
	private final JdbcTemplate jdbc;
	private final EventPublisher events;
	private final SignInThrottle throttle;
	private final Sessions sessions;

	public UserService(UserRepository users, PasswordEncoder passwords, AccessTokens tokens, JdbcTemplate jdbc,
			EventPublisher events, SignInThrottle throttle, Sessions sessions) {
		this.throttle = throttle;
		this.sessions = sessions;
		this.users = users;
		this.passwords = passwords;
		this.tokens = tokens;
		this.jdbc = jdbc;
		this.events = events;
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
	// Deliberately no transaction. A failure is recorded and then the sign-in throws, and a transaction
	// around both would roll the record back with the refusal, so failures would never count (ADR 0058).
	public Sessions.Session signIn(String email, String rawPassword, String clientAddress) {
		// Before the password is looked at, so a throttled guess that happens to be right is refused too.
		throttle.check(email, clientAddress);
		User user = users.findByEmail(email.trim().toLowerCase()).orElse(null);

		String hash = user != null ? user.getPasswordHash()
				// A real BCrypt hash of a value nobody knows, so the comparison costs what a real one
				// costs. Skipping it here is what makes the timing side-channel.
				: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

		boolean matches = passwords.matches(rawPassword, hash);
		if (user == null || !matches || user.getStatus() != User.Status.ACTIVE) {
			throttle.failed(email, clientAddress);
			log.info("Rejected a sign-in attempt for {}", email);
			throw new UnauthenticatedException();
		}

		throttle.succeeded(email, clientAddress);
		return sessions.start(user);
	}

	/**
	 * The caller's account, for the {@code /me} paths.
	 *
	 * @throws UnauthenticatedException for a closed account: its tokens stay valid until they expire, and
	 *                                  the account they name is no longer one anybody can use (ADR 0053)
	 */
	/** Every session of this account ends at its next refresh, this one included. ADR 0059. */
	@Transactional
	public void signOutEverywhere(UUID id) {
		require(id);
		sessions.revokeAll(id, "signed out everywhere");
	}

	@Transactional(readOnly = true)
	public User require(UUID id) {
		User user = users.findWithAddresses(id)
				.orElseThrow(() -> ResourceNotFoundException.of("User", id.toString()));
		if (user.getStatus() == User.Status.CLOSED) {
			throw new UnauthenticatedException("This account has been closed");
		}
		return user;
	}

	/**
	 * Closes the caller's account. Irreversible. See ADR 0053.
	 *
	 * <p>Asks for the password as well as the token, so a token copied from somewhere cannot destroy an
	 * account on its own. A wrong password is the sign-in's answer, INVALID_CREDENTIALS.
	 *
	 * <p>One transaction: the identity is erased, any staff roles are revoked on the record, the emails
	 * kept about the account are deleted, and {@code AccountClosed} goes to the outbox. Either all of it
	 * happens or none of it, and the other services are told only about a closure that committed.
	 */
	@Transactional
	public void close(UUID id, String rawPassword) {
		User user = require(id);
		if (!passwords.matches(rawPassword, user.getPasswordHash())) {
			log.info("Refused to close account {}: wrong password", id);
			throw new UnauthenticatedException();
		}

		List<String> roles = user.getRoles();
		Instant now = Instant.now();
		user.close(now);
		users.flush();

		// A staff account can be closed too, and its roles go with it. Recorded as grant-role.sh records
		// a revocation (ADR 0047), so the history of who held a role does not just stop.
		for (String role : roles) {
			jdbc.update("""
					insert into role_grants (user_id, role, action, reason, granted_by)
					values (?, ?, 'REVOKE', 'account closed by its holder', 'account-closure')""", id, role);
		}
		// What was emailed to them, and anything still waiting to be: the subject lines name their orders,
		// the bodies their refunds. Support's view of their emails ends here.
		int notices = jdbc.update("delete from notices where customer_id = ?", id.toString());

		// Its sessions end at their next refresh, within one access token's lifetime (ADR 0059).
		sessions.revokeAll(id, "account closed");
		events.publish(Topics.USER_EVENTS, new AccountClosed(EventMetadata.of(AccountClosed.TYPE, id), id.toString()));
		log.info("Closed account {}: erased its identity, revoked {} role(s), deleted {} notice(s)", id,
				roles.size(), notices);
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
