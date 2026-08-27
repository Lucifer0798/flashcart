package com.flashcart.gateway;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
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
