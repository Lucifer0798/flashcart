package com.flashcart.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public-path rules, tested directly.
 *
 * <p>These existed as one line of stream code and shipped a bug in the narrow direction the same
 * afternoon: {@code /stock/*} was written against a matcher that only understood {@code **} and
 * exact equality, so availability -- documented as public in three places -- answered 401 to
 * everybody. The obvious repair was to widen the rule to {@code /stock/**}, which would have fixed
 * the symptom and quietly published the movement ledger and the receive path underneath it.
 *
 * <p>Both mistakes are one character wide. That is what these are for.
 */
class OperatorFilterTest {

	private static final String STOCK = "GET /api/v1/inventory/stock/*";

	@Nested
	@DisplayName("a single star is exactly one more segment")
	class SingleStar {

		@Test
		void matchesOneSegment() {
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1")).isTrue();
		}

		@Test
		@DisplayName("and not the movement ledger below it")
		void doesNotMatchDeeper() {
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1/movements"))
					.isFalse();
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1/receive"))
					.isFalse();
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1/adjust"))
					.isFalse();
		}

		@Test
		@DisplayName("and not the collection above it -- listing every SKU is not availability")
		void doesNotMatchTheCollection() {
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock")).isFalse();
		}

		@Test
		@DisplayName("and not an empty segment")
		void doesNotMatchTrailingSlash() {
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/")).isFalse();
		}

		@Test
		@DisplayName("and not a path that merely starts with the same characters")
		void doesNotMatchPrefixCollision() {
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stockpile")).isFalse();
		}
	}

	@Nested
	@DisplayName("a star in the middle is one segment, anywhere")
	class MidPathStar {

		private static final String HISTORY = "GET /api/v1/orders/*/history";

		// Before the matcher understood a mid-path star this rule fell through to equality and
		// matched nothing, which in a default-deny filter left the endpoint operator-only however
		// carefully it was listed -- and the list looked correct. That is why ADR 0035 exists, and it
		// is a fact about the old matcher rather than something assertable against this one.
		@Test
		void matchesTheSegmentItStandsFor() {
			assertThat(OperatorFilter.matches(HISTORY, "GET", "/api/v1/orders/FC-12345/history"))
					.isTrue();
		}

		@Test
		@DisplayName("and not two segments where one was asked for")
		void doesNotMatchDeeper() {
			assertThat(OperatorFilter.matches(HISTORY, "GET", "/api/v1/orders/a/b/history")).isFalse();
		}

		@Test
		@DisplayName("and not a shorter or longer path")
		void lengthMustAgree() {
			assertThat(OperatorFilter.matches(HISTORY, "GET", "/api/v1/orders/FC-1")).isFalse();
			assertThat(OperatorFilter.matches(HISTORY, "GET", "/api/v1/orders/FC-1/history/extra"))
					.isFalse();
		}

		@Test
		@DisplayName("and not an empty segment, which names nothing")
		void doesNotMatchEmptySegment() {
			assertThat(OperatorFilter.matches(HISTORY, "GET", "/api/v1/orders//history")).isFalse();
		}

		@Test
		@DisplayName("the method qualifier still applies")
		void methodStillApplies() {
			assertThat(OperatorFilter.matches(HISTORY, "POST", "/api/v1/orders/FC-1/history")).isFalse();
			assertThat(OperatorFilter.matches("POST /api/v1/orders/*/cancel", "POST",
					"/api/v1/orders/FC-1/cancel")).isTrue();
		}

		@Test
		@DisplayName("adding this changed nothing about a trailing star")
		void trailingStarIsUntouched() {
			// The branch was added after every branch that existed before it, so a rule that already
			// matched cannot be diverted into it. This is the rule that keeps the movement ledger
			// closed, asserted again here because it is the one that would hurt to lose.
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1")).isTrue();
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1/movements"))
					.isFalse();
		}
	}

	@Nested
	@DisplayName("the method qualifier")
	class Method {

		@Test
		@DisplayName("reading availability is public, writing to the same path is not")
		void bindsToTheVerb() {
			assertThat(OperatorFilter.matches(STOCK, "GET", "/api/v1/inventory/stock/OPS-1")).isTrue();
			assertThat(OperatorFilter.matches(STOCK, "POST", "/api/v1/inventory/stock/OPS-1")).isFalse();
			assertThat(OperatorFilter.matches(STOCK, "DELETE", "/api/v1/inventory/stock/OPS-1")).isFalse();
		}

		@Test
		void isCaseInsensitive() {
			assertThat(OperatorFilter.matches(STOCK, "get", "/api/v1/inventory/stock/OPS-1")).isTrue();
		}

		@Test
		@DisplayName("an unqualified rule accepts any verb")
		void isOptional() {
			assertThat(OperatorFilter.matches("/actuator/**", "POST", "/actuator/loggers")).isTrue();
		}
	}

	@Nested
	@DisplayName("a double star is any depth")
	class DoubleStar {

		@Test
		void matchesDeeply() {
			assertThat(OperatorFilter.matches("/actuator/**", "GET", "/actuator/health")).isTrue();
			assertThat(OperatorFilter.matches("/actuator/**", "GET", "/actuator/health/readiness"))
					.isTrue();
		}

		@Test
		void doesNotMatchASibling() {
			assertThat(OperatorFilter.matches("/actuator/**", "GET", "/actuatorx/health")).isFalse();
		}
	}

	@Nested
	@DisplayName("the signed-in category")
	class SignedIn {

		@Test
		@DisplayName("reads are listed, and the collection and one identifier both match")
		void coversTheReadPaths() {
			assertThat(OperatorFilter.matches("GET /api/v1/shipments", "GET", "/api/v1/shipments"))
					.isTrue();
			assertThat(OperatorFilter.matches("GET /api/v1/shipments/*", "GET", "/api/v1/shipments/TRK1"))
					.isTrue();
			assertThat(OperatorFilter.matches("GET /api/v1/shipments/order/*", "GET",
					"/api/v1/shipments/order/FC-1")).isTrue();
		}

		@Test
		@DisplayName("dispatch and deliver are POSTs, so no GET rule reaches them")
		void doesNotCoverTheWarehouseActions() {
			for (String rule : new String[] { "GET /api/v1/shipments", "GET /api/v1/shipments/*",
					"GET /api/v1/shipments/order/*" }) {
				assertThat(OperatorFilter.matches(rule, "POST", "/api/v1/shipments/TRK1/dispatch"))
						.as(rule + " must not match dispatch")
						.isFalse();
				assertThat(OperatorFilter.matches(rule, "POST", "/api/v1/shipments/TRK1/deliver"))
						.as(rule + " must not match deliver")
						.isFalse();
			}
		}

		@Test
		@DisplayName("nor does the single star reach them even as a GET, being a segment too deep")
		void singleStarStopsAboveTheActions() {
			assertThat(OperatorFilter.matches("GET /api/v1/shipments/*", "GET",
					"/api/v1/shipments/TRK1/dispatch")).isFalse();
		}
	}

	@Test
	@DisplayName("a rule with no star is an exact path")
	void exactPath() {
		String info = "/api/v1/inventory/_info";
		assertThat(OperatorFilter.matches(info, "GET", "/api/v1/inventory/_info")).isTrue();
		assertThat(OperatorFilter.matches(info, "GET", "/api/v1/inventory/_information")).isFalse();
		assertThat(OperatorFilter.matches(info, "GET", "/api/v1/inventory/_info/detail")).isFalse();
	}
}
