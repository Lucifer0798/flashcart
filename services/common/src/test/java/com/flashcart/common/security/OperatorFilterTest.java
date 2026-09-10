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

	@Test
	@DisplayName("a rule with no star is an exact path")
	void exactPath() {
		String info = "/api/v1/inventory/_info";
		assertThat(OperatorFilter.matches(info, "GET", "/api/v1/inventory/_info")).isTrue();
		assertThat(OperatorFilter.matches(info, "GET", "/api/v1/inventory/_information")).isFalse();
		assertThat(OperatorFilter.matches(info, "GET", "/api/v1/inventory/_info/detail")).isFalse();
	}
}
