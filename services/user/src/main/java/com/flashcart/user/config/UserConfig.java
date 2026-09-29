package com.flashcart.user.config;

import java.time.Duration;
import java.util.List;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.common.security.OperatorFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class UserConfig {

	/**
	 * BCrypt at the library default cost.
	 *
	 * <p>Deliberately slow, which is the point of it, and the reason sign-in runs the encoder even
	 * for an email that does not exist — see {@code UserService.signIn}.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AccessTokens accessTokens(
			@Value("${flashcart.security.jwt.secret}") String secret,
			@Value("${flashcart.security.jwt.ttl:PT12H}") Duration ttl) {
		return new AccessTokens(secret, ttl);
	}

	/**
	 * Default-deny, on the one service where nothing was open.
	 *
	 * <p>This is the last of the six, and it is the only one where the audit found nothing wrong. Every
	 * endpoint here either has to be public — you cannot require a token to obtain your first token —
	 * or already reads {@code Authorization} and resolves the caller from it. Adding the filter closes
	 * no hole.
	 *
	 * <p>It is here for the direction of the list, which is ADR 0022's whole argument: a service that
	 * enumerates what is <em>open</em> ships a forgotten endpoint closed. This service has five
	 * endpoints and the next one to be added would have shipped open, checked only if its author
	 * remembered — which is exactly how the order service (ADR 0035) and catalog (ADR 0036) came to be
	 * exposed. Neither of those was a decision either.
	 *
	 * <p><strong>The two public POSTs are the reason this service is different.</strong> Register and
	 * sign in are unauthenticated by necessity, not by oversight, and they are the only writes in the
	 * platform that must stay that way. They are listed by exact path and method, so a future
	 * {@code POST /api/v1/users/anything} is not covered by them.
	 *
	 * <p>The three {@code /me} paths are signed-in, which is the dangerous category: passing this
	 * filter is not the whole check. Each resolves the caller from the token and touches only that
	 * caller's row — the filter cannot do that part, because a path does not say who owns what is
	 * behind it. Here {@code /me} makes the ownership question unusually easy, since the path names the
	 * caller rather than a row id.
	 */
	@Bean
	public FilterRegistrationBean<OperatorFilter> operatorFilter(AccessTokens tokens) {
		FilterRegistrationBean<OperatorFilter> registration = new FilterRegistrationBean<>(
				new OperatorFilter(tokens, List.of(
						// You cannot present a token to get your first one.
						"POST /api/v1/users",
						"POST /api/v1/users/sessions",
						"/api/v1/user/_info",
						// Compose health-checks this, and a container that cannot answer restarts
						// forever.
						"/actuator/**"),
						List.of(
								"GET /api/v1/users/me",
								"PATCH /api/v1/users/me",
								"POST /api/v1/users/me/addresses")));
		registration.addUrlPatterns("/*");
		// After the correlation id filter, so a refusal is still traceable to a request.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return registration;
	}

	@Bean
	public OpenAPI userOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart User API")
				.version("v1")
				.description("Accounts, sign-in and addresses. The only service that stores a "
						+ "password and the only one that mints a token."));
	}
}
