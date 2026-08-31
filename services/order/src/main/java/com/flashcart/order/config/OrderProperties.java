package com.flashcart.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param catalogUrl    where to price a basket
 * @param inventoryUrl  where to hold stock
 * @param requestTimeout how long to wait on either before treating silence as an unknown outcome
 * @param reconciler    settings for the job that cleans up orders whose hold lapsed
 */
@ConfigurationProperties(prefix = "flashcart.order")
public record OrderProperties(
		String catalogUrl,
		String inventoryUrl,
		Duration requestTimeout,
		Reconciler reconciler) {

	public OrderProperties {
		catalogUrl = catalogUrl == null ? "http://localhost:8081" : catalogUrl;
		inventoryUrl = inventoryUrl == null ? "http://localhost:8085" : inventoryUrl;
		// Short on purpose. A checkout that hangs for thirty seconds has already lost the customer,
		// and the reservation key makes a retry safe — so failing fast is strictly better than
		// waiting, which is not true of every synchronous call.
		requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
		reconciler = reconciler == null ? new Reconciler(true, 200) : reconciler;
	}

	public record Reconciler(boolean enabled, int batchSize) {

		public Reconciler {
			batchSize = batchSize <= 0 ? 200 : batchSize;
		}
	}
}
