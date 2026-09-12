package com.flashcart.user.config;

import com.flashcart.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.UUID;

/**
 * Says so, loudly, if this deployment is carrying the development operator without having asked for
 * it.
 *
 * <p>The account is created only by {@code db/seed/V900__development_operator.sql}, which only the
 * {@code demo} profile puts on the Flyway path. So finding it here while that profile is off means
 * it arrived some other way — a database copied from a development machine, a seed run by hand, a
 * profile removed after the fact — and the deployment is carrying a credential whose password is
 * readable in a public repository.
 *
 * <p>That is precisely the case the old seed comment warned about and could do nothing to detect: a
 * seeded credential nobody remembers is worse than no credential.
 *
 * <h2>Why this logs rather than refuses to start</h2>
 *
 * <p>Refusing would be the louder signal and the wrong trade. This is a condition in data, not in
 * configuration, and data drifts: turning it into a failed startup converts a security note into an
 * outage, potentially during a deployment that changed nothing relevant. An ERROR line naming the
 * account and the fix is loud enough to act on and cannot itself take the service down.
 *
 * <p>See ADR 0024.
 */
@Component
public class SeededOperatorCheck {

	private static final Logger log = LoggerFactory.getLogger(SeededOperatorCheck.class);

	/** Fixed in the seed, so it is the same row everywhere it exists. */
	static final UUID DEVELOPMENT_OPERATOR = UUID.fromString("00000000-0000-4000-8000-00000000000f");

	/** The one profile that puts db/seed on the Flyway path, and so the one that creates the row. */
	static final String DEMO_PROFILE = "demo";

	private final UserRepository users;
	private final Environment environment;

	public SeededOperatorCheck(UserRepository users, Environment environment) {
		this.users = users;
		this.environment = environment;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void checkOnStartup() {
		if (isUnexpectedlyPresent()) {
			log.error("""
					This deployment contains the seeded development operator \
					(operator@flashcart.local, id {}), whose password is published in this \
					repository's db/seed migration. The `demo` profile is not active, so it was not \
					created here -- the database it came from was. Delete the row, or treat every \
					operator-only endpoint on this platform as public.""", DEVELOPMENT_OPERATOR);
		}
	}

	/**
	 * Separated from the logging so it can be tested without capturing log output, the same way
	 * {@code OperatorFilter.matches} and {@code User.parseRoles} are.
	 *
	 * <p>Both halves matter and they fail differently: forgetting the profile check makes this shout
	 * on every ordinary compose run until people stop reading it, and forgetting the row check makes
	 * it silent exactly when it is needed.
	 */
	boolean isUnexpectedlyPresent() {
		if (Arrays.asList(environment.getActiveProfiles()).contains(DEMO_PROFILE)) {
			// Asked for by name. This is the compose stack, and the harnesses need the account.
			return false;
		}
		return users.existsById(DEVELOPMENT_OPERATOR);
	}
}
