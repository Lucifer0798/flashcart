package com.flashcart.inventory.service;

/**
 * The gate, switched off.
 *
 * <p>Every answer is {@link Decision#UNKNOWN}, so every request goes to PostgreSQL and the service
 * behaves exactly as it did before Phase 7. Used when Redis is deliberately not configured, and —
 * more usefully — by the load tests in Phase 10, which need to measure the same code path with the
 * gate in and out to say what it actually bought.
 */
public class DisabledAvailabilityGate implements AvailabilityGate {

	@Override
	public Decision tryAdmit(String sku, int quantity) {
		return Decision.UNKNOWN;
	}

	@Override
	public void release(String sku, int quantity) {
	}

	@Override
	public void warm(String sku, int available) {
	}

	@Override
	public void invalidate(String sku) {
	}
}
