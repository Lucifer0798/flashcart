package com.flashcart.order.client;

import java.math.BigDecimal;

/**
 * Where an order gets its prices.
 *
 * <p>Prices are fetched, never accepted from the client. A checkout that trusts a price in the
 * request body is a checkout anyone can discount to zero, and the effective price — which may be a
 * live flash-sale price — is catalog's to decide, not the caller's.
 */
public interface CatalogClient {

	/**
	 * @throws com.flashcart.common.error.ResourceNotFoundException when the SKU is unknown
	 * @throws CatalogUnavailableException when catalog could not be reached
	 */
	PricedProduct priceOf(String sku);

	/**
	 * @param effectivePrice what a shopper pays right now, already accounting for any live flash
	 *                       sale. The order stores this, not the base price.
	 */
	record PricedProduct(String sku, String name, BigDecimal effectivePrice, String currency,
			boolean onFlashSale) {
	}
}
