package com.flashcart.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.flashcart.common.error.TooManyAttemptsException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limits failed sign-ins, so that guessing a password is slow (ADR 0058), and password-reset requests, so
 * that the reset form cannot be used to make the platform email strangers (ADR 0060).
 *
 * <p>Three sign-in limits, because each catches what the others cannot:
 *
 * <ul>
 *   <li><strong>One account, one client</strong> -- tight. Somebody guessing one account from one place is
 *       stopped quickly, and the account's real owner, signing in from somewhere else, is not.</li>
 *   <li><strong>One client, any accounts</strong> -- looser. Credential stuffing tries many accounts once
 *       each, which the first limit never sees.</li>
 *   <li><strong>One account, any clients</strong> -- loosest. A guess spread across many machines. This is
 *       the one an attacker could use to lock somebody out by failing on purpose, so it is set high and a
 *       password reset still works while it holds.</li>
 * </ul>
 *
 * <p>And one on reset requests, per client: every request counts, whether or not the address has an
 * account, and the refusal depends only on the client. Counting only the requests that send an email would
 * make the limit arrive sooner for addresses that have accounts, which would itself say who has one.
 *
 * <p>Counted by the address as typed, whether or not it has an account, so a refusal says no more about who
 * shops here than sign-in's own answer does. Stored as hashes: the table never holds the addresses people
 * typed, many of them mistakes or other people's, nor where they typed them from.
 */
@Component
public class AttemptThrottle {

	private static final Logger log = LoggerFactory.getLogger(AttemptThrottle.class);

	/** One limit: at most {@code failures} in {@code window}. */
	record Limit(String scope, int failures, Duration window) {
	}

	private final JdbcTemplate jdbc;
	private final MeterRegistry meters;
	private final Limit pair;
	private final Limit client;
	private final Limit account;
	private final Limit resetClient;

	public AttemptThrottle(JdbcTemplate jdbc, MeterRegistry meters,
			@Value("${flashcart.password-reset.limit.client.requests:10}") int resetRequests,
			@Value("${flashcart.password-reset.limit.client.window:PT1H}") Duration resetWindow,
			@Value("${flashcart.sign-in.limit.account-and-client.failures:5}") int pairFailures,
			@Value("${flashcart.sign-in.limit.account-and-client.window:PT15M}") Duration pairWindow,
			@Value("${flashcart.sign-in.limit.client.failures:30}") int clientFailures,
			@Value("${flashcart.sign-in.limit.client.window:PT15M}") Duration clientWindow,
			@Value("${flashcart.sign-in.limit.account.failures:50}") int accountFailures,
			@Value("${flashcart.sign-in.limit.account.window:PT1H}") Duration accountWindow) {
		this.jdbc = jdbc;
		this.meters = meters;
		this.pair = new Limit("account-and-client", pairFailures, pairWindow);
		this.client = new Limit("client", clientFailures, clientWindow);
		this.account = new Limit("account", accountFailures, accountWindow);
		this.resetClient = new Limit("reset-client", resetRequests, resetWindow);
	}

	/**
	 * Refuses a reset request from a client that has made too many. Checked before the address is looked up,
	 * and the refusal names no address, so it says nothing about any account. ADR 0060.
	 *
	 * @throws TooManyAttemptsException saying when trying again is worth it
	 */
	public void checkResetRequest(String clientAddress) {
		Optional<Long> blocked = blockedFor(resetClient, key(resetClient, null, clientAddress));
		if (blocked.isPresent()) {
			meters.counter("flashcart.user.password-reset.throttled").increment();
			log.info("Password reset request refused: too many from this client; retry in {}s", blocked.get());
			throw new TooManyAttemptsException("Too many password reset requests from here. Try again later.",
					blocked.get());
		}
	}

	/** Counts one reset request against its client, whatever the address and whatever happened next. */
	public void resetRequested(String clientAddress) {
		jdbc.update("insert into throttled_attempts (key_hash) values (?)", key(resetClient, null, clientAddress));
	}

	/**
	 * Refuses before the password is looked at. While a limit holds, even the right password is refused:
	 * otherwise the throttle would still confirm a correct guess, and only slow the wrong ones.
	 *
	 * @throws TooManyAttemptsException naming no limit, and saying when trying again is worth it
	 */
	public void check(String email, String clientAddress) {
		long wait = 0;
		String scope = null;
		for (Limit limit : List.of(pair, client, account)) {
			Optional<Long> blocked = blockedFor(limit, key(limit, email, clientAddress));
			if (blocked.isPresent() && blocked.get() > wait) {
				wait = blocked.get();
				scope = limit.scope();
			}
		}
		if (scope != null) {
			meters.counter("flashcart.user.sign-in.throttled", "limit", scope).increment();
			log.info("Sign-in refused: too many recent failures ({} limit); retry in {}s", scope, wait);
			// The same words for every limit. Saying which one held would tell a guesser whether the address
			// is being attacked from elsewhere.
			throw new TooManyAttemptsException("Too many failed sign-ins. Try again later, or reset your password.",
					wait);
		}
	}

	/** Counts one failure against all three limits. */
	public void failed(String email, String clientAddress) {
		for (Limit limit : List.of(pair, client, account)) {
			jdbc.update("insert into throttled_attempts (key_hash) values (?)", key(limit, email, clientAddress));
		}
	}

	/**
	 * A success from this client clears this client's count for the account, so a mistyped password or two
	 * does not linger. The other two counts stand: one success from one place says nothing about the rest.
	 */
	public void succeeded(String email, String clientAddress) {
		jdbc.update("delete from throttled_attempts where key_hash = ?", key(pair, email, clientAddress));
	}

	/** Failures older than the longest window can no longer count, and are deleted. */
	@Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
	public void prune() {
		// The longest of all four, the reset limit's included: a shorter one would delete attempts that can
		// still count against it.
		Duration longest = List.of(pair.window(), client.window(), account.window(), resetClient.window()).stream()
				.max(Duration::compareTo).orElseThrow();
		try {
			jdbc.update("delete from throttled_attempts where attempted_at < now() - make_interval(secs => ?)",
					longest.toSeconds());
		}
		catch (DataAccessException ex) {
			log.error("Pruning throttled attempts failed; will retry on the next tick", ex);
		}
	}

	/** Seconds until this limit lets one more attempt through, or empty if it already would. */
	private Optional<Long> blockedFor(Limit limit, String key) {
		// The failure that has to age out before there is room: the limit-th most recent one in the window.
		// Its wait is computed by the database, against the same clock that stamped it: mixing the JVM's
		// clock with the database's is a mistake the notice sender has already made once.
		List<Long> wait = jdbc.queryForList("""
				select ceil(extract(epoch from attempted_at + make_interval(secs => ?) - now()))::bigint
				  from throttled_attempts
				 where key_hash = ? and attempted_at > now() - make_interval(secs => ?)
				 order by attempted_at desc
				 offset ? limit 1""", Long.class, limit.window().toSeconds(), key, limit.window().toSeconds(),
				limit.failures() - 1);
		return wait.isEmpty() ? Optional.empty() : Optional.of(Math.max(1, wait.get(0)));
	}

	private static String key(Limit limit, String email, String clientAddress) {
		String normalised = email == null ? "" : email.trim().toLowerCase();
		String raw = switch (limit.scope()) {
			case "account-and-client" -> "pair|" + normalised + "|" + clientAddress;
			case "client" -> "client|" + clientAddress;
			case "reset-client" -> "reset-client|" + clientAddress;
			default -> "account|" + normalised;
		};
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required of every Java platform", ex);
		}
	}
}
