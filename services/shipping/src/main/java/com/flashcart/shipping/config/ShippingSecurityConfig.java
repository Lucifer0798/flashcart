package com.flashcart.shipping.config;

import java.time.Duration;
import java.util.List;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.common.security.OperatorAccessLog;
import com.flashcart.common.security.OperatorFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Requires an operator for shipments and their dispatch and delivery transitions.
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
public class ShippingSecurityConfig {

	@Bean
	public AccessTokens accessTokens(
			@Value("${flashcart.security.jwt.secret}") String secret,
			@Value("${flashcart.security.jwt.ttl:PT12H}") Duration ttl) {
		return new AccessTokens(secret, ttl);
	}

	/**
	 * Writes to this service's own database, on the same connection the read came from -- so
	 * recording an operator's access adds no dependency the read did not already have. See ADR 0025.
	 */
	@Bean
	public OperatorAccessLog operatorAccessLog(JdbcTemplate jdbc) {
		return new OperatorAccessLog(jdbc);
	}

	@Bean
	public FilterRegistrationBean<OperatorFilter> operatorFilter(AccessTokens tokens) {
		FilterRegistrationBean<OperatorFilter> registration = new FilterRegistrationBean<>(
				new OperatorFilter(tokens, List.of(
						"/api/v1/shipping/_info",
						"/actuator/**"),
						// Reads only, and GET only. Dispatch and deliver are POSTs to paths under
						// /shipments/*, so they fall through to the operator default -- a customer
						// may watch their parcel move, not move it. ShipmentController checks whose
						// parcel each of these is. See ADR 0023.
						List.of(
								"GET /api/v1/shipments",
								"GET /api/v1/shipments/*",
								"GET /api/v1/shipments/order/*")));
		registration.addUrlPatterns("/*");
		// After the correlation id filter, so a refusal is still traceable to a request.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return registration;
	}
}
