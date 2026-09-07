package com.flashcart.order.client;

import com.flashcart.common.error.UpstreamUnavailableException;

/**
 * Catalog could not be reached.
 *
 * <p>Less dangerous than an unreachable inventory: nothing has been held yet, so failing the
 * checkout outright is safe and leaves no orphaned state.
 */
public class CatalogUnavailableException extends UpstreamUnavailableException {

	public CatalogUnavailableException(String message, Throwable cause) {
		super("CATALOG_UNAVAILABLE", message, cause);
	}
}
