package com.flashcart.inventory.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.common.security.CallerIdentity;
import com.flashcart.common.security.OperatorAccessLog;
import com.flashcart.common.security.OperatorAccessLogReader;
import com.flashcart.common.security.OperatorAccessRetention;
import com.flashcart.common.security.OperatorFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Requires an operator for stock, reservations and the movement ledger.
 *
 * <p><strong>Default-deny.</strong> {@link OperatorFilter} protects everything this service exposes
 * except the paths named below, so an endpoint added tomorrow is closed until somebody decides
 * otherwise. Listing what to protect instead would mean a forgotten endpoint ships open, and only one
 * of those two mistakes is survivable.
 *
 * <p>Until now this service had no authentication whatsoever. Nothing reached it from outside in this
 * deployment -- but that was a claim about a compose file, and compose publishes it on its own host
 * port. See ADR 0022.
 */
@Configuration
public class InventorySecurityConfig {

	@Bean
	public AccessTokens accessTokens(
			@Value("${flashcart.security.jwt.secret}") String secret,
			@Value("${flashcart.security.jwt.ttl:PT12H}") Duration ttl) {
		return new AccessTokens(secret, ttl);
	}

	@Bean
	public CallerIdentity callerIdentity(AccessTokens tokens) {
		return new CallerIdentity(tokens);
	}

	/**
	 * Writes to this service's own database, on the same connection the read came from -- so recording a
	 * staff read adds no dependency the read did not already have. See ADR 0025 and ADR 0067.
	 */
	@Bean
	public OperatorAccessLog operatorAccessLog(JdbcTemplate jdbc, ObjectProvider<MeterRegistry> registry) {
		return new OperatorAccessLog(jdbc, registry.getIfAvailable(SimpleMeterRegistry::new));
	}

	/** Takes the writer as well as the table, because support reading this log is recorded like any other read. */
	@Bean
	public OperatorAccessLogReader operatorAccessLogReader(JdbcTemplate jdbc, CallerIdentity caller,
			OperatorAccessLog accessLog) {
		return new OperatorAccessLogReader(jdbc, caller, accessLog);
	}

	/**
	 * Off unless configured: an audit trail that deletes itself because nobody set a property is the decision
	 * ADR 0026 said should not be made silently. Runs on the scheduling this service already has switched on.
	 */
	@Bean
	public OperatorAccessRetention operatorAccessRetention(JdbcTemplate jdbc,
			@Value("${flashcart.audit.retention:#{null}}") Duration retention,
			@Value("${flashcart.audit.retention-batch-size:500}") int batchSize) {
		return new OperatorAccessRetention(jdbc, retention, batchSize);
	}

	@Bean
	public FilterRegistrationBean<OperatorFilter> operatorFilter(AccessTokens tokens) {
		FilterRegistrationBean<OperatorFilter> registration = new FilterRegistrationBean<>(
				new OperatorFilter(tokens, List.of(
						"/api/v1/inventory/_info",
						"/actuator/**",
						// Availability is public: a shopper legitimately needs to know how many are left, and
						// a count of a public product carries nobody's personal data. Everything else here --
						// creating stock, receiving, adjusting, the ledger, reservations -- either moves
						// quantities or reveals the whole catalogue's position, and needs an operator.
						"GET /api/v1/inventory/stock/*"),
						// The waitlist, and only the waitlist: the one thing here a shopper does. Each acts as
						// the token's subject -- there is no customer id to supply -- so a valid token is the
						// whole check the handler needs. See ADR 0044.
						List.of(
								"POST /api/v1/inventory/waitlist",
								"GET /api/v1/inventory/waitlist/mine",
								"DELETE /api/v1/inventory/waitlist/*",
								// Your own data as a file (ADR 0066). The handler takes whose from the token; the request names nobody.
								"GET /api/v1/inventory/_export/mine",
								// Your own entries in this service's access log (ADR 0056). The reader takes whose
								// they are from the token; the request names nobody.
								"GET /api/v1/inventory/_access-log/mine"),
						Map.of(
								// The fifteen warehouse endpoints, by name. This was /api/v1/inventory/** until the
								// access log arrived on the same prefix: rules here can only widen, so a wildcard
								// would have let the warehouse past the filter to who read a customer's data.
								AccessTokens.WAREHOUSE, List.of(
										"/api/v1/inventory/stock",
										"/api/v1/inventory/stock/**",
										"/api/v1/inventory/allocations",
										"/api/v1/inventory/allocations/**",
										"/api/v1/inventory/reservations",
										"/api/v1/inventory/reservations/**"),
								// Who looked at a customer's reservations is support's question, not the
								// warehouse's. See ADR 0067.
								AccessTokens.SUPPORT, List.of("GET /api/v1/inventory/_access-log"))));
		registration.addUrlPatterns("/*");
		// After the correlation id filter, so a refusal is still traceable to a request.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return registration;
	}
}
