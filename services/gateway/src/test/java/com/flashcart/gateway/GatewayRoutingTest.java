package com.flashcart.gateway;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.NameResolverProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the routing table itself.
 *
 * <p>A gateway whose context starts but whose routes are misconfigured fails in the least useful
 * way possible: every downstream call 404s at the edge, and nothing in any service's logs explains
 * why. Asserting the parsed routes catches a typo in the YAML before a deploy does.
 */
@SpringBootTest
class GatewayRoutingTest {

	@Autowired
	private RouteLocator routeLocator;

	@Autowired
	private HttpClientProperties httpClient;

	/** The one NettyRoutingFilter sends every proxied request with. */
	@Autowired
	private HttpClient routingClient;

	@Test
	@DisplayName("connections to each service are a fixed pool of 500, not one per request in flight")
	void theConnectionPoolIsBounded() {
		// Elastic, the default, opened 3,594 connections at once to the user service at a sale's opening, and the
		// kernel's resets turned shoppers away. See ADR 0074.
		assertThat(httpClient.getPool().getType()).isEqualTo(HttpClientProperties.Pool.PoolType.FIXED);
		assertThat(httpClient.getPool().getMaxConnections()).isEqualTo(500);
		assertThat(httpClient.getPool().getAcquireTimeout()).isEqualTo(45_000L);
		assertThat(httpClient.getPool().isMetrics()).isTrue();
	}

	@Test
	@DisplayName("the client that carries every route keeps a service's address for five seconds at most")
	void addressesAreLookedUpAgainWithinSeconds() {
		// Netty's resolver otherwise keeps an answer for its whole TTL, 600 seconds from Docker's DNS, so a service
		// that came back on a new address was refused through the gateway for up to ten minutes. See ADR 0075.
		NameResolverProvider resolver = routingClient.configuration().getNameResolverProvider();

		assertThat(resolver).as("a resolver configured on the routing client").isNotNull();
		assertThat(resolver.cacheMaxTimeToLive()).isEqualTo(Duration.ofSeconds(5));
		// Only the cap moves. A negative answer is still not cached, so a service that is back is found at once.
		assertThat(resolver.cacheNegativeTimeToLive()).isEqualTo(Duration.ZERO);
	}

	@Test
	@DisplayName("all six domain services have a route")
	void everyServiceIsRouted() {
		List<Route> routes = routeLocator.getRoutes().collectList().block();

		assertThat(routes).extracting(Route::getId)
				.containsExactlyInAnyOrder("catalog", "order", "user", "payment", "inventory", "shipping");
	}

	@Test
	@DisplayName("each route points at its own service, not at a stale copy of a neighbour's URI")
	void routesPointAtDistinctTargets() {
		List<Route> routes = routeLocator.getRoutes().collectList().block();

		// A copy-paste slip in the URI list is invisible in review and sends one service's traffic
		// to another. Distinctness is the cheap invariant that catches it.
		assertThat(routes).extracting(route -> route.getUri().toString()).doesNotHaveDuplicates();
	}
}
