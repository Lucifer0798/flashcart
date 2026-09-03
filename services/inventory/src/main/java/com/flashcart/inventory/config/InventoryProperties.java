package com.flashcart.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param reservationTtl    how long a hold lasts when the caller does not ask for a specific window
 * @param maxReservationTtl the ceiling on a caller-supplied TTL, so no client can park stock forever
 * @param strategy          which locking approach the reserve path uses
 * @param sweeper           background reclaim settings
 * @param lazyReclaimLimit  how many expired holds one reserve call will reclaim inline before
 *                          getting on with its own work; bounded so an unlucky request never
 *                          inherits an unbounded backlog
 */
@ConfigurationProperties(prefix = "flashcart.inventory")
public record InventoryProperties(
		Duration reservationTtl,
		Duration maxReservationTtl,
		ReservationStrategy strategy,
		Sweeper sweeper,
		Gate gate,
		int lazyReclaimLimit) {

	public InventoryProperties {
		reservationTtl = reservationTtl == null ? Duration.ofMinutes(15) : reservationTtl;
		maxReservationTtl = maxReservationTtl == null ? Duration.ofHours(1) : maxReservationTtl;
		strategy = strategy == null ? ReservationStrategy.ATOMIC_UPDATE : strategy;
		sweeper = sweeper == null ? new Sweeper(true, 500) : sweeper;
		gate = gate == null ? new Gate(true, Duration.ofSeconds(60)) : gate;
		lazyReclaimLimit = lazyReclaimLimit <= 0 ? 50 : lazyReclaimLimit;
	}

	/**
	 * @param enabled  whether this instance runs the sweeper at all
	 * @param batchSize how many expired holds one sweep reclaims
	 */
	public record Sweeper(boolean enabled, int batchSize) {

		public Sweeper {
			batchSize = batchSize <= 0 ? 500 : batchSize;
		}
	}

	/**
	 * The Redis availability gate.
	 *
	 * @param enabled false falls back to a no-op gate, so every reserve goes to the database — the
	 *                baseline Phase 10 measures against
	 * @param ttl     how long an estimate lives before it is re-read from PostgreSQL. This is the
	 *                self-healing interval: a counter that has drifted low, and is therefore refusing
	 *                stock that exists, repairs itself within one TTL.
	 */
	public record Gate(boolean enabled, Duration ttl) {

		public Gate {
			ttl = ttl == null ? Duration.ofSeconds(60) : ttl;
		}
	}

	/** How the reserve path defends the stock counter. Both are correct; they differ under load. */
	public enum ReservationStrategy {

		/**
		 * A single conditional {@code UPDATE ... WHERE available >= quantity}. The default, and the
		 * right answer: the check and the write are one statement, so the row lock lives for
		 * microseconds and there is no read-then-write window to race in.
		 */
		ATOMIC_UPDATE,

		/**
		 * {@code SELECT ... FOR UPDATE}, then check, then write. Equally correct and materially
		 * slower under contention, because the lock is held for the rest of the transaction rather
		 * than for one statement. Kept so Phase 10 can measure the difference rather than assert it.
		 */
		PESSIMISTIC_LOCK
	}
}
