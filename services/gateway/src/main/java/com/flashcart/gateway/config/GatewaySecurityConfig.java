package com.flashcart.gateway.config;

import java.time.Duration;

import com.flashcart.common.security.AccessTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway verifies tokens; it never issues them.
 *
 * <p>It holds the same HMAC secret as the user service, which means it technically could mint one —
 * an honest cost of the symmetric choice, recorded in ADR 0021 along with what would change it.
 */
@Configuration
public class GatewaySecurityConfig {

	@Bean
	public AccessTokens accessTokens(
			@Value("${flashcart.security.jwt.secret}") String secret,
			@Value("${flashcart.security.jwt.ttl:PT12H}") Duration ttl) {
		return new AccessTokens(secret, ttl);
	}
}
