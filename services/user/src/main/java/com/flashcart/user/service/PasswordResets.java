package com.flashcart.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.user.domain.User;
import com.flashcart.user.notice.NoticeKind;
import com.flashcart.user.notice.Notices;
import com.flashcart.user.notice.Notices.Notice;
import com.flashcart.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resetting a forgotten password by email. See ADR 0057.
 *
 * <p>Three rules shape all of it:
 *
 * <ul>
 *   <li><strong>Asking reveals nothing.</strong> A request for an address with no account gets the same
 *       answer as one for an address with one. Otherwise the reset form becomes a way to ask whether
 *       somebody shops here, which sign-in was built not to answer.</li>
 *   <li><strong>The token is never stored.</strong> The request queues an email that carries only the
 *       reset's id. The sender mints the token when it sends, keeps its SHA-256, and puts the token only
 *       in the email. Support's view of the queue and anyone who can read the database see nothing that
 *       opens the account.</li>
 *   <li><strong>It works once, briefly.</strong> Thirty minutes, single use, and using it cancels the
 *       account's other outstanding resets.</li>
 * </ul>
 */
@Service
public class PasswordResets {

	private static final Logger log = LoggerFactory.getLogger(PasswordResets.class);

	/** One error for every way a token can be wrong, so none of them can be told apart. */
	static final String INVALID = "RESET_TOKEN_INVALID";

	private final JdbcTemplate jdbc;
	private final Notices notices;
	private final UserRepository users;
	private final PasswordEncoder passwords;
	private final Sessions sessions;
	private final AttemptThrottle throttle;
	private final Duration ttl;
	private final Duration minInterval;
	private final String link;
	private final SecureRandom random = new SecureRandom();

	public PasswordResets(JdbcTemplate jdbc, Notices notices, UserRepository users, PasswordEncoder passwords,
			Sessions sessions, AttemptThrottle throttle,
			@Value("${flashcart.password-reset.ttl:PT30M}") Duration ttl,
			@Value("${flashcart.password-reset.min-interval:PT1M}") Duration minInterval,
			@Value("${flashcart.password-reset.link:https://flashcart.local/reset-password?token=}") String link) {
		this.jdbc = jdbc;
		this.notices = notices;
		this.users = users;
		this.passwords = passwords;
		this.sessions = sessions;
		this.throttle = throttle;
		this.ttl = ttl;
		this.minInterval = minInterval;
		this.link = link;
	}

	/**
	 * Asks for a reset email. Returns nothing, and the caller answers the same way whatever happened here.
	 *
	 * <p>Only an active account gets one. A second request inside {@code min-interval} sends nothing more,
	 * so the form cannot be used to fill somebody's inbox.
	 */
	@Transactional
	public void request(String email, String clientAddress) {
		// Per client, before the address is looked up, and counted whatever the address is (ADR 0060).
		throttle.checkResetRequest(clientAddress);
		throttle.resetRequested(clientAddress);
		Optional<User> found = users.findByEmail(email.trim().toLowerCase())
				.filter(user -> user.getStatus() == User.Status.ACTIVE);
		if (found.isEmpty()) {
			log.info("Password reset asked for an address with no active account; nothing sent");
			return;
		}
		User user = found.get();
		Boolean recent = jdbc.queryForObject("""
				select exists (select 1 from password_resets
				                where user_id = ? and requested_at > now() - make_interval(secs => ?))""",
				Boolean.class, user.getId(), minInterval.toSeconds());
		if (Boolean.TRUE.equals(recent)) {
			log.info("Password reset for {} asked again within {}; not sending another", user.getId(), minInterval);
			return;
		}

		UUID resetId = UUID.randomUUID();
		Instant now = Instant.now();
		Instant expires = now.plus(ttl);
		jdbc.update("insert into password_resets (id, user_id, requested_at, expires_at) values (?, ?, ?, ?)",
				resetId, user.getId(), Timestamp.from(now), Timestamp.from(expires));
		// Not sent past the expiry: a link that no longer works is worse than no email.
		notices.receive(new Notice("password-reset:" + resetId, NoticeKind.PASSWORD_RESET, user.getId().toString(),
				null, now, Map.of("resetId", resetId.toString()), expires));
		log.info("Password reset {} requested for {}", resetId, user.getId());
	}

	/**
	 * Mints the token for a reset about to be emailed, keeping only its hash.
	 *
	 * <p>Called by the sender inside the transaction that sends. A retry after a refused send mints a fresh
	 * one, so only the token in the email that actually went out works.
	 *
	 * @return the link to put in the email, or empty when the reset can no longer be used
	 */
	public Optional<String> mint(String resetId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		int updated = jdbc.update("""
				update password_resets set token_hash = ?
				 where id = ?::uuid and used_at is null and expires_at > now()""", hash(token), resetId);
		return updated == 1 ? Optional.of(link + token) : Optional.empty();
	}

	/**
	 * Sets a new password from a token. Every way a token can be wrong -- unknown, expired, used, or for an
	 * account no longer active -- is the same 400.
	 */
	@Transactional
	public void confirm(String token, String newPassword) {
		// Claimed and spent in one statement, so two uses racing each other cannot both succeed.
		List<UUID> claimed = jdbc.queryForList("""
				update password_resets r set used_at = now()
				  from users u
				 where r.token_hash = ? and r.used_at is null and r.expires_at > now()
				   and u.id = r.user_id and u.status = 'ACTIVE'
				returning r.user_id""", UUID.class, hash(token));
		if (claimed.isEmpty()) {
			throw new BadRequestException(INVALID, "That reset link is not valid. It may have expired or already "
					+ "been used; ask for a new one.");
		}
		UUID userId = claimed.get(0);
		User user = users.findById(userId).orElseThrow();
		user.changePassword(passwords.encode(newPassword));
		// Whoever had the old password may also have a session. It ends at its next refresh (ADR 0059).
		sessions.revokeAll(userId, "password reset");
		// Any other link still in somebody's inbox stops working too.
		int others = jdbc.update("update password_resets set used_at = now() where user_id = ? and used_at is null",
				userId);
		log.info("Password reset for {}; {} other outstanding reset(s) cancelled", userId, others);
	}

	/** Kept a day past expiry, so a "was that me?" asked the next morning can still be checked, then deleted. */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void prune() {
		try {
			jdbc.update("delete from password_resets where expires_at < now() - interval '1 day'");
		}
		catch (DataAccessException ex) {
			log.error("Pruning password resets failed; will retry on the next tick", ex);
		}
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required of every Java platform", ex);
		}
	}
}
