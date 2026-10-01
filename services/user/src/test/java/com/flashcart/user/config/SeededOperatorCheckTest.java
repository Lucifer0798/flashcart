package com.flashcart.user.config;

import com.flashcart.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The safety net for a development credential arriving somewhere it was never seeded.
 *
 * <p>Both halves of the condition are tested because they fail in opposite, equally useless ways: a
 * missing profile check shouts on every ordinary compose run until people stop reading it, and a
 * missing row check stays quiet exactly when it matters.
 */
class SeededOperatorCheckTest {

	private final UserRepository users = mock(UserRepository.class);

	private SeededOperatorCheck check(String... activeProfiles) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(activeProfiles);
		return new SeededOperatorCheck(users, environment);
	}

	@Test
	@DisplayName("the row where no profile asked for it is the case worth shouting about")
	void presentWithoutTheProfile() {
		when(users.existsById(SeededOperatorCheck.DEVELOPMENT_OPERATOR)).thenReturn(true);

		assertThat(check().unexpectedlyPresent()).containsExactly("operator@flashcart.local");
	}

	@Test
	@DisplayName("under demo it was asked for by name, so it is not a finding")
	void presentUnderDemoIsExpected() {
		when(users.existsById(SeededOperatorCheck.DEVELOPMENT_OPERATOR)).thenReturn(true);

		assertThat(check("demo").unexpectedlyPresent()).isEmpty();
	}

	@Test
	@DisplayName("and demo alongside other profiles still counts as having asked")
	void demoAmongOthers() {
		when(users.existsById(SeededOperatorCheck.DEVELOPMENT_OPERATOR)).thenReturn(true);

		assertThat(check("prod", "demo", "metrics").unexpectedlyPresent()).isEmpty();
	}

	@Test
	@DisplayName("no row is nothing to report")
	void absentIsFine() {
		when(users.existsById(SeededOperatorCheck.DEVELOPMENT_OPERATOR)).thenReturn(false);

		assertThat(check().unexpectedlyPresent()).isEmpty();
	}

	@Test
	@DisplayName("every seeded account is reported, not only the operator")
	void reportsAllSeededAccounts() {
		// ADR 0039 added one account per narrow role, carrying the same published password. Keyed on
		// the operator's id alone, this check would have gone on naming the operator while three more
		// credentials sat beside it unmentioned -- which is the failure the class exists to prevent.
		SeededOperatorCheck.SEEDED_ACCOUNTS.keySet().forEach(id ->
				when(users.existsById(id)).thenReturn(true));

		assertThat(check().unexpectedlyPresent())
				.containsExactly("catalog@flashcart.local", "operator@flashcart.local",
						"support@flashcart.local", "warehouse@flashcart.local");
	}

	@Test
	@DisplayName("and one of them alone is still a finding")
	void oneNonOperatorAccountIsEnough() {
		when(users.existsById(SeededOperatorCheck.DEVELOPMENT_OPERATOR)).thenReturn(false);
		when(users.existsById(java.util.UUID.fromString("00000000-0000-4000-8000-00000000000a")))
				.thenReturn(true);

		// The case the old single-id check could not see at all.
		assertThat(check().unexpectedlyPresent()).containsExactly("warehouse@flashcart.local");
	}

	@Test
	@DisplayName("under demo it does not even ask the database")
	void demoSkipsTheQuery() {
		check("demo").unexpectedlyPresent();

		// Not an optimisation -- it is why a compose stack cannot trip this on a slow first boot.
		verify(users, never()).existsById(any());
	}
}
