package com.flashcart.gateway.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway looks a service's address up again within seconds, not minutes. See ADR 0075.
 *
 * <p>Spring Cloud Gateway leaves the HTTP client's resolver at Reactor Netty's default: Netty's own asynchronous DNS
 * resolver, which keeps each answer for as long as its TTL says, with no upper bound of its own. Docker's embedded
 * DNS answers with a TTL of 600 seconds. When the user service crash-looped and came back on a new address, the gateway
 * went on connecting to the old one, refused, for up to ten minutes, while every other container already resolved the
 * new one.
 *
 * <p>The cap shortens how long an answer is kept and changes nothing else: the resolver is still Netty's, still
 * asynchronous, and connections already in the pool (ADR 0074) are reused as before. A lookup happens only when a new
 * connection is opened after the cap has passed, against a DNS server inside the same host.
 *
 * <p>The JDK's resolver, the other way to get a short cache, was not chosen. Netty calls it on the event loop, and
 * while a service is stopped a lookup of its name took 3.9 seconds to fail. Every route sharing that event loop would
 * wait with it.
 */
@Configuration(proxyBeanMethods = false)
public class UpstreamDnsConfig {

	@Bean
	HttpClientCustomizer upstreamDnsCacheCap(
			@Value("${flashcart.gateway.dns.cache-max-time-to-live:5s}") Duration cacheMaxTimeToLive) {
		return client -> client.resolver(spec -> spec.cacheMaxTimeToLive(cacheMaxTimeToLive));
	}
}
