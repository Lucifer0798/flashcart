package com.flashcart.common.security;

import java.time.Duration;
import java.util.List;

import com.flashcart.common.error.OperatorRequiredException;
import com.flashcart.common.error.UnauthenticatedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The ownership decision, now that there is one copy of it instead of two.
 *
 * <p>These were previously only reachable through a running payment or shipping service, which meant
 * the decision itself was tested twice over via HTTP and never directly. The interesting cases are
 * the ones where the answer is yes <em>and</em> something must be written down, because that pairing
 * is the part a second copy would have drifted on.
 */
class CustomerDataAccessTest {

	private static final AccessTokens TOKENS =
			new AccessTokens("a-development-secret-long-enough-for-hs256-signing", Duration.ofHours(1));

	private final OperatorAccessLog accessLog = mock(OperatorAccessLog.class);
	private final CustomerDataAccess access =
			new CustomerDataAccess(new CallerIdentity(TOKENS), accessLog);

	private static String shopper(String id) {
		return "Bearer " + TOKENS.issue(id, id + "@example.test");
	}

	private static String operator(String id) {
		return "Bearer " + TOKENS.issue(id, id + "@example.test", List.of(AccessTokens.OPERATOR));
	}

	@Test
	@DisplayName("your own row needs no role and is not worth recording")
	void ownerReadsWithoutAudit() {
		assertThat(access.mayRead(shopper("alice"), "alice", "READ_PAYMENT", "FC-1")).isTrue();

		verify(accessLog, never()).record(any(), any(), any(), any());
	}

	@Test
	@DisplayName("somebody else's is refused outright for an ordinary customer")
	void strangerIsRefused() {
		assertThat(access.mayRead(shopper("bob"), "alice", "READ_PAYMENT", "FC-1")).isFalse();
	}

	@Test
	@DisplayName("and a refusal records nothing: nothing was disclosed")
	void refusalRecordsNothing() {
		access.mayRead(shopper("bob"), "alice", "READ_PAYMENT", "FC-1");

		verify(accessLog, never()).record(any(), any(), any(), any());
	}

	@Test
	@DisplayName("an operator may, and the access is recorded with who looked and whose it was")
	void operatorReadIsRecorded() {
		assertThat(access.mayRead(operator("ops"), "alice", "READ_PAYMENT", "FC-1")).isTrue();

		verify(accessLog).record("ops", "READ_PAYMENT", "FC-1", "alice");
	}

	@Test
	@DisplayName("an operator reading their own row is just a customer, and is not recorded")
	void operatorReadingTheirOwnIsNotRecorded() {
		assertThat(access.mayRead(operator("ops"), "ops", "READ_PAYMENT", "FC-1")).isTrue();

		verify(accessLog, never()).record(any(), any(), any(), any());
	}

	@Test
	@DisplayName("no token is 401, not a refusal -- there is nobody to refuse")
	void noTokenIsUnauthenticated() {
		assertThatThrownBy(() -> access.mayRead("Bearer nonsense", "alice", "READ_PAYMENT", "FC-1"))
				.isInstanceOf(UnauthenticatedException.class);
	}

	// --- listings -------------------------------------------------------------------------------

	@Test
	@DisplayName("no customerId means your own, which is the only way a customer can ask")
	void noParameterMeansYourOwn() {
		assertThat(access.subjectOf(shopper("alice"), null, "READ_PAYMENT_LIST", "payments"))
				.isEqualTo("alice");

		verify(accessLog, never()).record(any(), any(), any(), any());
	}

	@Test
	@DisplayName("naming yourself is not asking for somebody else")
	void namingYourselfIsFine() {
		assertThat(access.subjectOf(shopper("alice"), "alice", "READ_PAYMENT_LIST", "payments"))
				.isEqualTo("alice");

		verify(accessLog, never()).record(any(), any(), any(), any());
	}

	@Test
	@DisplayName("naming somebody else is refused, not quietly narrowed to your own rows")
	void namingAnotherIsForbidden() {
		assertThatThrownBy(() -> access.subjectOf(shopper("bob"), "alice", "READ_PAYMENT_LIST", "payments"))
				.isInstanceOf(OperatorRequiredException.class)
				.hasMessageContaining("payments");

		// The dangerous bug this guards is returning bob's own rows instead: that reads as success,
		// so whoever wrote the call believes it worked and finds out much later.
		verify(accessLog, never()).record(any(), any(), any(), any());
	}

	@Test
	@DisplayName("an operator may name somebody else, and it goes on the record")
	void operatorListingIsRecorded() {
		assertThat(access.subjectOf(operator("ops"), "alice", "READ_PAYMENT_LIST", "payments"))
				.isEqualTo("alice");

		verify(accessLog).record("ops", "READ_PAYMENT_LIST", null, "alice");
	}
}
