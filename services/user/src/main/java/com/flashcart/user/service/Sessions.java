package com.flashcart.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.domain.User;
import com.flashcart.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions: a short access token, and a refresh token that keeps the session going. See ADR 0059.
 *
 * <p>Every service verifies an access token for itself, which is what keeps the platform's services from
 * depending on this one for every request. The price is that a token cannot be withdrawn once issued. So
 * access tokens are short, fifteen minutes, and what can be withdrawn is the means of getting the next one.
 *
 * <p>A refresh token is 32 random bytes, kept here only as its SHA-256. It works once: exchanging it marks
 * it used and returns a new one in the same family. A used token coming back means somebody else holds a
 * copy -- the client that rotated it would never present it again -- so the whole family is revoked, the
 * thief's copy and the owner's with it, and the owner signs in again.
 */
@Service
public class Sessions {

	private static final Logger log = LoggerFactory.getLogger(Sessions.class);

	/** An access token and the refresh token that follows it. */
	public record Session(String accessToken, String refreshToken) {
	}

	private final JdbcTemplate jdbc;
	private final UserRepository users;
	private final AccessTokens tokens;
	private final MeterRegistry meters;
	private final Duration refreshTtl;
	private final SecureRandom random = new SecureRandom();

	public Sessions(JdbcTemplate jdbc, UserRepository users, AccessTokens tokens, MeterRegistry meters,
			@Value("${flashcart.security.refresh.ttl:P30D}") Duration refreshTtl) {
		this.jdbc = jdbc;
		this.users = users;
		this.tokens = tokens;
		this.meters = meters;
		this.refreshTtl = refreshTtl;
	}

	public Duration refreshTtl() {
		return refreshTtl;
	}

	/** A new session, after a successful sign-in: a new family. */
	@Transactional
	public Session start(User user) {
		return issue(user, UUID.randomUUID());
	}

	/**
	 * Exchanges a refresh token for a new session. Every failure is the same 401, as sign-in's are.
	 *
	 * <p>The account is read again, so a role revoked or an account closed since the last refresh takes
	 * effect here, within one access token's lifetime.
	 */
	@Transactional(noRollbackFor = UnauthenticatedException.class)
	public Session refresh(String refreshToken) {
		String hash = hash(refreshToken);
		// Claimed in one statement, so two exchanges racing each other cannot both succeed.
		List<Map<String, Object>> claimed = jdbc.queryForList("""
				update refresh_tokens set used_at = now()
				 where token_hash = ? and used_at is null and revoked_at is null and expires_at > now()
				returning user_id, family_id""", hash);
		if (claimed.isEmpty()) {
			reuseCheck(hash);
			throw new UnauthenticatedException("That refresh token is not valid; sign in again");
		}
		UUID userId = (UUID) claimed.get(0).get("user_id");
		UUID family = (UUID) claimed.get(0).get("family_id");
		User user = users.findById(userId).filter(found -> found.getStatus() == User.Status.ACTIVE).orElse(null);
		if (user == null) {
			revokeFamily(family, "account no longer active");
			throw new UnauthenticatedException("That refresh token is not valid; sign in again");
		}
		return issue(user, family);
	}

	/** Signs out the one session this refresh token belongs to. Idempotent, and silent about unknown tokens. */
	@Transactional
	public void signOut(String refreshToken) {
		jdbc.queryForList("select family_id from refresh_tokens where token_hash = ?", UUID.class, hash(refreshToken))
				.forEach(family -> revokeFamily(family, "signed out"));
	}

	/**
	 * Every session of this account ends at its next refresh. Called for "sign out everywhere", a password
	 * reset and an account closure. Joins the caller's transaction, so it happens with what caused it.
	 *
	 * @return how many refresh tokens were revoked
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int revokeAll(UUID userId, String why) {
		int revoked = jdbc.update("update refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null",
				userId);
		log.info("Revoked {} refresh token(s) for {}: {}", revoked, userId, why);
		return revoked;
	}

	/** Expired a day ago or more: no longer of use even to explain a refusal. */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void prune() {
		try {
			jdbc.update("delete from refresh_tokens where expires_at < now() - interval '1 day'");
		}
		catch (DataAccessException ex) {
			log.error("Pruning refresh tokens failed; will retry on the next tick", ex);
		}
	}

	private Session issue(User user, UUID family) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.update("""
				insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at)
				values (?, ?, ?, ?, now() + make_interval(secs => ?))""",
				UUID.randomUUID(), user.getId(), family, hash(refresh), refreshTtl.toSeconds());
		return new Session(tokens.issue(user.getId().toString(), user.getEmail(), user.getRoles()), refresh);
	}

	/** A token already used, presented again: somebody else has a copy. End the whole family. */
	private void reuseCheck(String hash) {
		List<UUID> reused = jdbc.queryForList(
				"select family_id from refresh_tokens where token_hash = ? and used_at is not null and revoked_at is null",
				UUID.class, hash);
		for (UUID family : reused) {
			meters.counter("flashcart.user.refresh.reused").increment();
			log.warn("Refresh token family {} presented a used token again; revoking the whole family", family);
			revokeFamily(family, "a used token was presented again");
		}
	}

	private void revokeFamily(UUID family, String why) {
		int revoked = jdbc.update("update refresh_tokens set revoked_at = now() where family_id = ? and revoked_at is null",
				family);
		log.info("Revoked {} refresh token(s) in family {}: {}", revoked, family, why);
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
