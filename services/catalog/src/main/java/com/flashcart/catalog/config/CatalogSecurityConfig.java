package com.flashcart.catalog.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.common.security.OperatorFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Anybody may browse the catalog. Only an operator may change it.
 *
 * <h2>What was open</h2>
 *
 * <p>This service had no authentication of any kind, and eleven endpoints that write. Anyone who could
 * reach the gateway could create a product, reprice one, delete one, invent a flash sale, set its
 * allocations, schedule it or cancel it — without an account.
 *
 * <p>The repricing is the one that matters.
 * ADR 0011 made the order
 * service ask catalog for each SKU's {@code effectivePrice} and copy it onto the order line, so that a
 * checkout cannot be discounted by a client that supplies its own price. That protection assumed the
 * catalog itself could not be edited by the person doing the buying. It could:
 *
 * <pre>
 * POST /api/v1/products      -> 201   (created at 999.00, no token)
 * PUT  /api/v1/products/{id} -> 200   (repriced to 0.01, no token)
 * POST /api/v1/orders                 -> total 0.01
 * </pre>
 *
 * <p>ADR 0022 closed exactly this on inventory, payment and shipping — "anybody who could reach the
 * gateway could receive five thousand units of stock … without an account" — and this service was not
 * in that change. It is the one that sets prices. See ADR 0036.
 *
 * <h2>Reads are public, and that is not an oversight</h2>
 *
 * <p>Every {@code GET} here is listed public, because a catalog nobody can browse is not a catalog:
 * the README's first example is an anonymous {@code curl} of the live flash sale. The filter is
 * default-deny, so every write falls through to the operator category without being named — which is
 * the property worth having when somebody adds a twelfth write endpoint.
 *
 * <p>The list is method-qualified for the same reason. {@code GET /api/v1/products/*} opens reading one
 * product and says nothing about {@code PUT} to the same path, which is how one rule can open a read
 * without opening the write beside it.
 */
@Configuration
public class CatalogSecurityConfig {

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
						// Browsing, in full. Reading a catalog needs no account and never did.
						"GET /api/v1/products",
						"GET /api/v1/products/*",
						// Two segments, so the single star above does not reach them.
						"GET /api/v1/products/sku/*",
						"GET /api/v1/products/slug/*",
						"GET /api/v1/categories",
						"GET /api/v1/categories/*",
						"GET /api/v1/flash-sales",
						// /active and /upcoming are one segment each, so the star covers them along
						// with /{idOrSlug}. Named here only in this comment, because a rule per
						// literal would be three rules that cannot disagree.
						"GET /api/v1/flash-sales/*",
						// Compose health-checks this, and a container that cannot answer restarts
						// forever.
						"/actuator/**",
						// Which build is behind the route; public on every service.
						"/api/v1/catalog/_info"),
						// Nothing is merely signed-in here. A shopper has no business editing a
						// catalogue, so the middle category -- the dangerous one, where passing the
						// filter is not the whole check -- is deliberately empty.
						List.of(),
						// Eleven writes, all of them this role's work. OPERATOR still passes, being a
						// superset, so nothing that worked yesterday stops -- see ADR 0038.
						Map.of(AccessTokens.CATALOG, List.of(
								"POST /api/v1/products",
								"PUT /api/v1/products/*",
								"DELETE /api/v1/products/*",
								"POST /api/v1/categories",
								"PUT /api/v1/categories/*",
								"DELETE /api/v1/categories/*",
								"POST /api/v1/flash-sales",
								"POST /api/v1/flash-sales/*/items",
								"DELETE /api/v1/flash-sales/*/items/*",
								"POST /api/v1/flash-sales/*/schedule",
								"POST /api/v1/flash-sales/*/cancel"))));
		registration.addUrlPatterns("/*");
		// After the correlation id filter, so a refusal is still traceable to a request.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return registration;
	}
}
