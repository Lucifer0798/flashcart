package com.flashcart.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param catalogUrl    where to price a basket
 * @param inventoryUrl  where to hold stock
 * @param requestTimeout how long to wait on either before treating silence as an unknown outcome
 * @param reconciler    settings for the two backstop jobs: the one that cleans up orders whose
 *                      hold lapsed, and the one that re-asks about an unanswered cancellation
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
		reconciler = reconciler == null ? new Reconciler(true, 200, Duration.ofMinutes(1)) : reconciler;
	}

	/**
	 * @param cancellationTimeout how long to let an order wait in {@code CANCELLATION_REQUESTED}
	 *                            before asking shipping again. Generous on purpose: the cost of
	 *                            asking too early is a duplicate command on a topic, and the cost of
	 *                            asking too late is a customer left wondering, so neither is urgent
	 *                            enough to justify chasing an answer that is merely in flight.
	 */
	public record Reconciler(boolean enabled, int batchSize, Duration cancellationTimeout) {

		public Reconciler {
			batchSize = batchSize <= 0 ? 200 : batchSize;
			cancellationTimeout = cancellationTimeout == null || cancellationTimeout.isNegative()
					? Duration.ofMinutes(1) : cancellationTimeout;
		}
	}
}
