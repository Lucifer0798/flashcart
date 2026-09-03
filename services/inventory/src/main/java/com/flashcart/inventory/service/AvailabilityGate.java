package com.flashcart.inventory.service;

/**
 * A cheap, fallible first opinion on whether a reservation is worth attempting.
 *
 * <h2>What this is for</h2>
 *
 * During a flash sale, most requests are refusals — that is the defining shape of the workload. Each
 * one currently costs a database round trip, a connection from a bounded pool, and a row lock, to
 * arrive at "no". Ten thousand people chasing five hundred units means the database spends the vast
 * majority of its effort saying no to people.
 *
 * <p>This gate answers the hopeless ones in memory, before the database is touched at all.
 *
 * <h2>The one rule</h2>
 *
 * <strong>The gate may only ever say "definitely not". It can never say "yes".</strong>
 *
 * <p>{@link Decision#ADMITTED} does not mean the units are yours — it means "not obviously
 * impossible, go and ask PostgreSQL". The conditional {@code UPDATE} in
 * {@link com.flashcart.inventory.repository.StockItemRepository#tryReserve} remains the only thing
 * that decides, exactly as it did before Redis existed, so
 * <a href="../../../../../../../../docs/adr/0006-conditional-update-prevents-overselling.md">ADR 0006</a>
 * is untouched.
 *
 * <p>That rule is what makes the whole thing safe to get wrong. The counter here is an estimate and
 * it will drift:
 *
 * <ul>
 *   <li>drifting <em>low</em> costs sales — a buyer is refused something that was actually there.
 *       Self-healing, because keys carry a TTL and are re-read from PostgreSQL when they lapse.</li>
 *   <li>drifting <em>high</em> costs nothing but a wasted database call, because PostgreSQL refuses
 *       and the caller hands the token back.</li>
 * </ul>
 *
 * <p>Neither can oversell. A cache that could only ever cause a false refusal is a cache you can
 * afford to have wrong, which is the entire reason it is allowed near this path.
 *
 * <h2>When Redis is down</h2>
 *
 * Every method degrades to {@link Decision#UNKNOWN}, the caller proceeds to PostgreSQL, and the
 * platform loses throughput rather than correctness. A Redis outage must never become an outage
 * here — it is an optimisation, and an optimisation that can take the system down is not one.
 */
public interface AvailabilityGate {

	/** What the gate thinks, which is never the last word. */
	enum Decision {

		/** Not obviously impossible. Go and ask PostgreSQL, which decides. */
		ADMITTED,

		/**
		 * Definitely not enough. Safe to refuse without touching the database — the only case where
		 * this gate saves anything, and the common one during a sale.
		 */
		REFUSED,

		/** No opinion: the counter is cold, or Redis is unreachable. Ask PostgreSQL. */
		UNKNOWN
	}

	/**
	 * Ask whether {@code quantity} of {@code sku} is worth attempting, decrementing the estimate if
	 * so.
	 *
	 * <p>On {@link Decision#ADMITTED} the caller <strong>must</strong> later call {@link #release} if
	 * PostgreSQL then refuses, or the estimate leaks downward and starts refusing real stock.
	 */
	Decision tryAdmit(String sku, int quantity);

	/** Hand units back to the estimate: a release, an expiry, or a PostgreSQL refusal after admission. */
	void release(String sku, int quantity);

    /**
     * Seed the estimate from the authoritative figure.
     *
     * <p>Only takes effect when the key is absent, so a warm-up can never clobber decrements made by
     * concurrent reservations. Losing the race merely leaves the counter cold for another moment.
     */
	void warm(String sku, int available);

	/** Forget an estimate outright, so the next request re-reads PostgreSQL. */
	void invalidate(String sku);
}
