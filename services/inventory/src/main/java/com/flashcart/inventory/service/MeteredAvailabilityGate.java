package com.flashcart.inventory.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts what the gate decided, so its value can be argued about with numbers.
 *
 * <p>A decorator rather than instrumentation inside {@link RedisAvailabilityGate}, because the
 * question "how often does the gate refuse" is equally worth asking of
 * {@link DisabledAvailabilityGate} — a run with the gate off is the control, and it is only a control
 * if it is measured the same way.
 *
 * <h2>What the three counters actually tell you</h2>
 *
 * <ul>
 * <li><strong>refused</strong> is the gate's entire reason to exist: requests answered without
 *     touching a connection. If this stays near zero under load, the gate is pure overhead.
 * <li><strong>admitted</strong> is the traffic that still reached PostgreSQL. Compared against the
 *     reservation outcomes below, it says how often the gate waved through something the database
 *     then refused — drift upward, which costs one wasted query.
 * <li><strong>unknown</strong> is the honest one. A cold counter is normal and self-corrects within
 *     the TTL, but a sustained rate means Redis is unreachable and the gate is doing nothing at all.
 *     Without this the platform would degrade to Phase 6 performance and look perfectly healthy,
 *     because degrading quietly is exactly what the gate is designed to do.
 * </ul>
 *
 * <p>Note what is deliberately not measured: whether the gate was "right". It cannot be wrong in a
 * way that matters — PostgreSQL still decides every reservation — so a correctness metric here would
 * imply a guarantee the design does not make.
 */
public class MeteredAvailabilityGate implements AvailabilityGate {

	private final AvailabilityGate delegate;
	private final Counter admitted;
	private final Counter refused;
	private final Counter unknown;
	private final Counter released;

	public MeteredAvailabilityGate(AvailabilityGate delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.admitted = decision(registry, "admitted");
		this.refused = decision(registry, "refused");
		this.unknown = decision(registry, "unknown");
		this.released = Counter.builder("flashcart.gate.released")
				.description("Units handed back to the estimate after a refusal, release or expiry")
				.register(registry);
	}

	private static Counter decision(MeterRegistry registry, String decision) {
		return Counter.builder("flashcart.gate.decisions")
				.description("Admission decisions taken before the database was consulted")
				.tag("decision", decision)
				.register(registry);
	}

	/**
	 * The gate underneath.
	 *
	 * <p>Exposed for the test that asserts this suite is exercising a real Redis gate rather than the
	 * no-op — a guard worth keeping, because without it every gate test would pass while proving
	 * nothing. Wrapping the gate in a decorator broke that check's mechanism, not its point.
	 */
	public AvailabilityGate delegate() {
		return delegate;
	}

	@Override
	public Decision tryAdmit(String sku, int quantity) {
		Decision decision = delegate.tryAdmit(sku, quantity);
		switch (decision) {
			case ADMITTED -> admitted.increment();
			case REFUSED -> refused.increment();
			case UNKNOWN -> unknown.increment();
		}
		return decision;
	}

	@Override
	public void release(String sku, int quantity) {
		// Counted because a release path that stops firing is how the estimate drifts permanently
		// downward and starts refusing stock that exists. Releases lagging far behind refusals is
		// the shape of that bug.
		released.increment();
		delegate.release(sku, quantity);
	}

	@Override
	public void warm(String sku, int available) {
		delegate.warm(sku, available);
	}

	@Override
	public void invalidate(String sku) {
		delegate.invalidate(sku);
	}
}
