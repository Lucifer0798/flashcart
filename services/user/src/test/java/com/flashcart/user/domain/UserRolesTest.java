package com.flashcart.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roles column is populated by a hand-written UPDATE -- there is deliberately no endpoint that
 * grants a role -- so it is parsed from whatever a human typed at a psql prompt at two in the
 * morning. These are the shapes that produces.
 */
class UserRolesTest {

	@Test
	@DisplayName("an ordinary shopper has none")
	void emptyIsNoRoles() {
		assertThat(User.parseRoles("")).isEmpty();
		assertThat(User.parseRoles("   ")).isEmpty();
		assertThat(User.parseRoles(null)).isEmpty();
	}

	@Test
	void oneRole() {
		assertThat(User.parseRoles("OPERATOR")).containsExactly("OPERATOR");
	}

	@Test
	@DisplayName("the space a human naturally leaves after a comma is not part of the role")
	void trimsAroundSeparators() {
		assertThat(User.parseRoles("OPERATOR, ADMIN")).containsExactly("OPERATOR", "ADMIN");
		assertThat(User.parseRoles(" OPERATOR ,ADMIN ")).containsExactly("OPERATOR", "ADMIN");
	}

	@Test
	@DisplayName("a stray comma does not become a role named nothing")
	void dropsEmptyEntries() {
		assertThat(User.parseRoles("OPERATOR,")).containsExactly("OPERATOR");
		assertThat(User.parseRoles(",OPERATOR,,ADMIN")).containsExactly("OPERATOR", "ADMIN");
	}

	@Test
	@DisplayName("matching is exact -- lowercase is a different role, and does not grant anything")
	void isCaseSensitive() {
		assertThat(User.parseRoles("operator")).containsExactly("operator").doesNotContain("OPERATOR");
	}
}
