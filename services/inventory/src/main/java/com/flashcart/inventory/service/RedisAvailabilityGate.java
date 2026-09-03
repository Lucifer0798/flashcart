package com.flashcart.inventory.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.DataAccessException;

/**
 * The Redis implementation of {@link AvailabilityGate}.
 *
 * <h2>Why a Lua script and not GET-then-DECR</h2>
 *
 * Reading the counter, comparing it, and then decrementing is the same read-then-write race that
 * makes the naive database implementation oversell — a hundred concurrent callers all read the same
 * value and all pass the check. Redis runs a script atomically against the whole keyspace, so the
 * comparison and the decrement are one indivisible step. It is the same argument as the conditional
 * {@code UPDATE}, in a different store.
 *
 * <p>(The consequence of losing that race here would only be a wasted database call rather than an
 * oversell — but a counter that drifts under load stops being useful, and correct is no harder.)
 *
 * <h2>Every failure is swallowed</h2>
 *
 * A Redis error returns {@link Decision#UNKNOWN} and the caller falls through to PostgreSQL. That is
 * the whole safety story: this component can be entirely broken and the platform still sells stock
 * correctly, just more slowly.
 */
public class RedisAvailabilityGate implements AvailabilityGate {

	private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityGate.class);

	private static final String KEY_PREFIX = "flashcart:avail:";

	/**
	 * Returns 1 admitted, 0 refused, -1 no opinion.
	 *
	 * <p>The {@code -1} case matters: a missing key means "I do not know", never "zero available".
	 * Treating an absent counter as empty would refuse every request the instant Redis was flushed
	 * or a key expired, which is exactly the failure mode this design exists to avoid.
	 */
	private static final RedisScript<Long> TRY_ADMIT = new DefaultRedisScript<>("""
			local current = redis.call('GET', KEYS[1])
			if not current then
			  return -1
			end
			local wanted = tonumber(ARGV[1])
			if tonumber(current) < wanted then
			  return 0
			end
			redis.call('DECRBY', KEYS[1], wanted)
			return 1
			""", Long.class);

	/**
	 * Gives units back, but only to a counter that still exists.
	 *
	 * <p>Creating one here would invent an estimate out of nothing — an {@code INCRBY} against a
	 * missing key starts from zero, so a release after the key expired would claim the SKU has
	 * exactly the released quantity available and nothing more.
	 */
	private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 0 then
			  return -1
			end
			return redis.call('INCRBY', KEYS[1], ARGV[1])
			""", Long.class);

	private final StringRedisTemplate redis;
	private final Duration ttl;

	public RedisAvailabilityGate(StringRedisTemplate redis, Duration ttl) {
		this.redis = redis;
		this.ttl = ttl;
	}

	@Override
	public Decision tryAdmit(String sku, int quantity) {
		try {
			Long result = redis.execute(TRY_ADMIT, List.of(key(sku)), String.valueOf(quantity));
			if (result == null || result == -1L) {
				return Decision.UNKNOWN;
			}
			return result == 1L ? Decision.ADMITTED : Decision.REFUSED;
		}
		catch (DataAccessException ex) {
			// Redis is an optimisation. An optimisation that can take the system down is not one.
			log.warn("Availability gate unavailable for {}; falling through to the database", sku);
			return Decision.UNKNOWN;
		}
	}

	@Override
	public void release(String sku, int quantity) {
		try {
			redis.execute(RELEASE, List.of(key(sku)), String.valueOf(quantity));
		}
		catch (DataAccessException ex) {
			// Losing a release only makes the estimate pessimistic, and the TTL repairs that. There
			// is nothing to escalate: the database has already been told the truth.
			log.warn("Could not return {} unit(s) of {} to the availability gate", quantity, sku);
		}
	}

	@Override
	public void warm(String sku, int available) {
		if (available < 0) {
			return;
		}
		try {
			// setIfAbsent, so a warm-up racing a live reservation never overwrites its decrement.
			// Losing that race just leaves the counter cold for another request.
			redis.opsForValue().setIfAbsent(key(sku), String.valueOf(available), ttl);
		}
		catch (DataAccessException ex) {
			log.debug("Could not warm the availability gate for {}", sku);
		}
	}

	@Override
	public void invalidate(String sku) {
		try {
			redis.delete(key(sku));
		}
		catch (DataAccessException ex) {
			log.debug("Could not invalidate the availability gate for {}", sku);
		}
	}

	private static String key(String sku) {
		return KEY_PREFIX + sku;
	}
}
