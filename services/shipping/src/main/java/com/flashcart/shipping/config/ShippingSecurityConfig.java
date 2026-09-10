package com.flashcart.shipping.config;

import java.time.Duration;
import java.util.List;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.common.security.OperatorFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
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

	@Bean
	public FilterRegistrationBean<OperatorFilter> operatorFilter(AccessTokens tokens) {
		FilterRegistrationBean<OperatorFilter> registration = new FilterRegistrationBean<>(
				new OperatorFilter(tokens, List.of(
						"/api/v1/shipping/_info",
						"/actuator/**")));
		registration.addUrlPatterns("/*");
		// After the correlation id filter, so a refusal is still traceable to a request.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return registration;
	}
}
