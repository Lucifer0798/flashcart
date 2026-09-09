package com.flashcart.user.config;

import java.time.Duration;

import com.flashcart.common.security.AccessTokens;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

	@Bean
	public OpenAPI userOpenApi() {
		return new OpenAPI().info(new Info()
				.title("FlashCart User API")
				.version("v1")
				.description("Accounts, sign-in and addresses. The only service that stores a "
						+ "password and the only one that mints a token."));
	}
}
