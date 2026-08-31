package com.flashcart.order.client;

import java.math.BigDecimal;

import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.web.CorrelationId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Fetches the price a shopper is actually paying right now, flash sale included. */
public class RestCatalogClient implements CatalogClient {

	private final RestClient http;

	public RestCatalogClient(RestClient http) {
		this.http = http;
	}

	@Override
	public PricedProduct priceOf(String sku) {
		try {
			ProductResponse product = http.get()
					.uri("/api/v1/products/sku/{sku}", sku)
					.headers(headers -> {
						String correlationId = CorrelationId.current();
						if (correlationId != null) {
							headers.set(CorrelationId.HEADER, correlationId);
						}
					})
					.exchange((request, response) -> {
						HttpStatusCode status = response.getStatusCode();
						if (status.is2xxSuccessful()) {
							return response.bodyTo(ProductResponse.class);
						}
						if (status.value() == HttpStatus.NOT_FOUND.value()) {
							throw ResourceNotFoundException.of("Product with SKU", sku);
						}
						throw new CatalogUnavailableException(
								"Catalog answered %s for SKU %s".formatted(status, sku), null);
					});

			return new PricedProduct(product.sku(), product.name(), product.effectivePrice(),
					product.currency(), product.onFlashSale());
		}
		catch (ResourceAccessException ex) {
			throw new CatalogUnavailableException("Catalog could not be reached for SKU " + sku, ex);
		}
	}

	/**
	 * Catalog's product shape, narrowed to what pricing an order needs.
	 *
	 * <p>{@code ignoreUnknown} on purpose: catalog is free to add fields to its response without
	 * that being a breaking change for this consumer, which is the entire point of not sharing a
	 * DTO class between the two services.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ProductResponse(
			String sku,
			String name,
			BigDecimal effectivePrice,
			String currency,
			boolean onFlashSale) {
	}
}
