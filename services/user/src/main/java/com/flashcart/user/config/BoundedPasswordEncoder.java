package com.flashcart.user.config;

import java.time.Duration;
import java.util.concurrent.Semaphore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A password encoder that runs no more hashes at once than there are cores to run them. See ADR 0071.
 *
 * <p>BCrypt is slow on purpose, in the CPU. When a sale opens and everybody signs in together, every request
 * thread is hashing at once. With all cores busy, a thread that holds one of the service's ten database
 * connections for a few milliseconds' query is descheduled for far longer. The pool runs dry, and requests
 * are shed with 503 although the database itself is idle. Measured: at 2,000 simultaneous sign-ins, 55 shed,
 * and 128 refreshes too, which do no hashing at all and only wanted a connection.
 *
 * <p>Capped, a request that would have competed for a core waits for one instead, holding nothing while it
 * waits. Hashing as many at once as there are cores is as fast as hashing can go, so the cap costs no
 * throughput, and the threads that do hold a connection get a core when they need one.
 *
 * <p>The wait has no timeout of its own. Only request threads call this, so at most Tomcat's 200 can be
 * waiting, which at full speed is a second or two of work, and the gateway and the client bound it anyway.
 *
 * <p>And it is measured (ADR 0073). A sign-in slow because it queued for a core looks, from outside, exactly like
 * one slow because the database is: these say which. All three are registered at construction, so a dashboard
 * shows zero rather than nothing before the first sign-in.
 *
 * <ul>
 *   <li>{@code flashcart.user.password-checks.waiting}: requests queued for a slot right now;</li>
 *   <li>{@code flashcart.user.password-checks.in-progress}: hashes running, never more than the slots;</li>
 *   <li>{@code flashcart.user.password-checks.slots}: the cap itself, for the panel beside them;</li>
 *   <li>{@code flashcart.user.password-checks.wait}: how long each check waited for a slot, as a histogram.</li>
 * </ul>
 */
public final class BoundedPasswordEncoder implements PasswordEncoder {

	private final PasswordEncoder delegate;
	private final Semaphore permits;
	private final Timer wait;

	public BoundedPasswordEncoder(PasswordEncoder delegate, int concurrency, MeterRegistry meters) {
		if (concurrency < 1) {
			throw new IllegalArgumentException("concurrency must be at least 1, was " + concurrency);
		}
		this.delegate = delegate;
		// Fair, so a sign-in that has waited longest is next: unfair would let a fresh arrival overtake it.
		Semaphore semaphore = new Semaphore(concurrency, true);
		this.permits = semaphore;
		Gauge.builder("flashcart.user.password-checks.waiting", semaphore, Semaphore::getQueueLength)
				.description("Password checks queued for a slot")
				.register(meters);
		Gauge.builder("flashcart.user.password-checks.in-progress", semaphore,
				held -> concurrency - held.availablePermits())
				.description("Password checks hashing now")
				.register(meters);
		Gauge.builder("flashcart.user.password-checks.slots", () -> concurrency)
				.description("Password checks allowed at once")
				.register(meters);
		this.wait = Timer.builder("flashcart.user.password-checks.wait")
				.description("Time a password check waited for a slot")
				// Buckets, so a percentile can be taken across instances and over any window in Prometheus.
				.publishPercentileHistogram()
				.minimumExpectedValue(Duration.ofMillis(1))
				.maximumExpectedValue(Duration.ofSeconds(60))
				.register(meters);
	}

	@Override
	public String encode(CharSequence rawPassword) {
		acquire();
		try {
			return delegate.encode(rawPassword);
		}
		finally {
			permits.release();
		}
	}

	@Override
	public boolean matches(CharSequence rawPassword, String encodedPassword) {
		acquire();
		try {
			return delegate.matches(rawPassword, encodedPassword);
		}
		finally {
			permits.release();
		}
	}

	@Override
	public boolean upgradeEncoding(String encodedPassword) {
		// Reads the stored hash's cost; no hashing, so no permit.
		return delegate.upgradeEncoding(encodedPassword);
	}

	private void acquire() {
		long started = System.nanoTime();
		try {
			permits.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to check a password", ex);
		}
		// Recorded for every check, including the ones that waited no time at all: a histogram of only the
		// waits would make one slow sign-in in a thousand look like the typical one.
		wait.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
	}
}
