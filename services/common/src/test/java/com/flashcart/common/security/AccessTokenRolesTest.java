package com.flashcart.common.security;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a role on a token means.
 *
 * <p>ADR 0038 split one `OPERATOR` that authorised thirty-one endpoints into three narrower roles. The
 * property that made that additive rather than a migration is the one most worth pinning: `OPERATOR`
 * satisfies every role, so no token already issued stopped working the day it shipped.
 */
class AccessTokenRolesTest {

	private static final String SECRET = "a-test-secret-long-enough-for-hs256-to-accept-it";

	private final AccessTokens tokens = new AccessTokens(SECRET, Duration.ofHours(1));

	private String tokenWith(String... roles) {
		return tokens.issue("user-1", "user-1@example.test", List.of(roles));
	}

	@Test
	@DisplayName("a narrow role satisfies itself and nothing else")
	void narrowRoleIsNarrow() {
		String warehouse = tokenWith(AccessTokens.WAREHOUSE);

		assertThat(tokens.hasAnyRole(warehouse, AccessTokens.WAREHOUSE)).isTrue();
		// The whole point of the split. Before it, the account that dispatches parcels could also
		// reprice every product and read any customer's audit trail.
		assertThat(tokens.hasAnyRole(warehouse, AccessTokens.CATALOG)).isFalse();
		assertThat(tokens.hasAnyRole(warehouse, AccessTokens.SUPPORT)).isFalse();
		assertThat(tokens.isOperator(warehouse)).isFalse();
	}

	@Test
	@DisplayName("OPERATOR satisfies every role, which is what makes the split additive")
	void operatorIsASuperset() {
		String operator = tokenWith(AccessTokens.OPERATOR);

		for (String role : List.of(AccessTokens.WAREHOUSE, AccessTokens.CATALOG, AccessTokens.SUPPORT)) {
			assertThat(tokens.hasAnyRole(operator, role)).as("OPERATOR should satisfy %s", role).isTrue();
		}
		// And a role it has never heard of, because the superset is about the holder rather than the
		// list: a rule added tomorrow does not lock out the accounts that already exist.
		assertThat(tokens.hasAnyRole(operator, "SOMETHING_ADDED_LATER")).isTrue();
	}

	@Test
	@DisplayName("holding several roles is holding each of them")
	void rolesCompose() {
		String both = tokenWith(AccessTokens.WAREHOUSE, AccessTokens.CATALOG);

		assertThat(tokens.hasAnyRole(both, AccessTokens.WAREHOUSE)).isTrue();
		assertThat(tokens.hasAnyRole(both, AccessTokens.CATALOG)).isTrue();
		assertThat(tokens.hasAnyRole(both, AccessTokens.SUPPORT)).isFalse();
	}

	@Test
	@DisplayName("a shopper holds nothing, and asking for no role in particular grants nothing")
	void noRolesMeansNoRoles() {
		String shopper = tokens.issue("cust-1", "cust-1@example.test", List.of());

		assertThat(tokens.hasAnyRole(shopper, AccessTokens.WAREHOUSE)).isFalse();
		// Called with no roles at all it must still be false rather than vacuously true, which is the
		// direction an "any of these" check gets wrong when the list is empty.
		assertThat(tokens.hasAnyRole(shopper)).isFalse();
	}

	@Test
	@DisplayName("an unusable token holds no role however the question is asked")
	void rubbishHoldsNothing() {
		for (String token : List.of("not-a-token", "", "a.b.c")) {
			assertThat(tokens.hasAnyRole(token, AccessTokens.WAREHOUSE))
					.as("token %s", token).isFalse();
		}
		assertThat(tokens.hasAnyRole(null, AccessTokens.WAREHOUSE)).isFalse();
	}

	@Test
	@DisplayName("the capability, not the role, is what callers ask for")
	void capabilityIsNamedForWhatItPermits() {
		CallerIdentity caller = new CallerIdentity(tokens);

		// SUPPORT is the role for this work today; OPERATOR carries it as a superset. A handler asks
		// "may this caller see somebody else" and never has to know which roles answer yes.
		assertThat(caller.maySeeAnotherCustomer("Bearer " + tokenWith(AccessTokens.SUPPORT))).isTrue();
		assertThat(caller.maySeeAnotherCustomer("Bearer " + tokenWith(AccessTokens.OPERATOR))).isTrue();
		assertThat(caller.maySeeAnotherCustomer("Bearer " + tokenWith(AccessTokens.WAREHOUSE))).isFalse();
		assertThat(caller.maySeeAnotherCustomer(null)).isFalse();
	}
}
