package com.flashcart.inventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import com.flashcart.inventory.service.AvailabilityGate;
import com.flashcart.inventory.service.MeteredAvailabilityGate;
import com.flashcart.inventory.service.RedisAvailabilityGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0016's central promise, end to end: with Redis unreachable the service still sells its stock,
 * sells all of it, and does not oversell — "losing Redis costs throughput and nothing else".
 *
 * <p>Until this existed that claim was checked only by {@code chaos/inject.sh}, which stops the
 * container against a running compose stack and is not part of CI. The JUnit suite had nothing:
 * {@link AvailabilityGateIT#coldCounterFallsThroughToTheDatabase} reaches {@code UNKNOWN} through a
 * missing key, which is Redis answering normally rather than Redis being gone.
 *
 * <p>The gate is pointed at a closed port rather than the shared container being stopped, and that is
 * not a shortcut. {@link AbstractInventoryIT#POSTGRES} and {@code REDIS} are static singletons whose
 * lifetime is the JVM's, precisely so that no one class can take a container away from the classes
 * that run after it — the failure that base class's javadoc documents at length. Stopping Redis here
 * would reintroduce exactly that.
 */
class AvailabilityGateUnreachableIT extends AbstractInventoryIT {

	/**
	 * Bound and immediately released, so it is a real port that is genuinely refusing connections.
	 * A hard-coded one could collide with something actually listening and pass for the wrong reason.
	 */
	private static final int DEAD_PORT;

	static {
		try (ServerSocket probe = new ServerSocket(0)) {
			DEAD_PORT = probe.getLocalPort();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@TestConfiguration
	static class PointTheGateAtNothing {

		/**
		 * Primary, so {@code InventoryConfig.availabilityGate}'s {@code ObjectProvider} hands this to
		 * the gate instead of the container-backed template. Everything else in the context — the
		 * database, the API, the sweeper — is untouched, which is the point: only the optimisation is
		 * broken.
		 */
		@Bean
		@Primary
		StringRedisTemplate unreachableRedisTemplate() {
			LettuceConnectionFactory connections = new LettuceConnectionFactory(
					new RedisStandaloneConfiguration("localhost", DEAD_PORT),
					LettuceClientConfiguration.builder()
							// The production posture, and the reason a dead Redis costs milliseconds
							// per call here rather than stalling the suite.
							.commandTimeout(Duration.ofMillis(250))
							.build());
			connections.afterPropertiesSet();
			return new StringRedisTemplate(connections);
		}
	}

	@Override
	protected boolean redisIsReachable() {
		return false;
	}

	@Autowired
	private AvailabilityGate gate;

	@Autowired
	private MeterRegistry meters;

	private double unknownDecisions() {
		return meters.get("flashcart.gate.decisions").tag("decision", "unknown").counter().count();
	}

	@Test
	@DisplayName("the gate really is the Redis one, and really cannot reach Redis")
	void theGateIsWiredToNothing() {
		// Without this the class could pass with the no-op gate, or against a working Redis, and
		// would be asserting nothing about an outage in either case.
		assertThat(gate).isInstanceOf(MeteredAvailabilityGate.class);
		assertThat(((MeteredAvailabilityGate) gate).delegate()).isInstanceOf(RedisAvailabilityGate.class);

		// The unreachability itself, asserted on the template rather than through the gate. Note that
		// tryAdmit returning UNKNOWN proves nothing on its own here: a healthy Redis answers UNKNOWN
		// for a SKU it has never seen, so that assertion would hold just as well if this class were
		// quietly talking to the real container.
		assertThatThrownBy(() -> redis.hasKey("flashcart:avail:" + uniqueSku("PROBE")))
				.isInstanceOf(DataAccessException.class);
		assertThat(gate.tryAdmit(uniqueSku("PROBE"), 1)).isEqualTo(AvailabilityGate.Decision.UNKNOWN);
	}

	@Test
	@DisplayName("with Redis gone the whole stock still sells, and not one unit more")
	void theServiceSellsOutCorrectlyWithoutTheGate() {
		String sku = uniqueSku("NOREDIS");
		createStock(sku, 3);
		double unknownBefore = unknownDecisions();

		assertThat(reserve(uniqueKey("order"), "cust-1", sku, 2).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(reserve(uniqueKey("order"), "cust-2", sku, 1).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		// The fourth unit does not exist, and PostgreSQL is the only thing that knows that now.
		ResponseEntity<Map> refused = reserveExpectingFailure(uniqueKey("order"), "cust-3", null, sku, 1);
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(refused.getBody()).containsEntry("code", "INSUFFICIENT_STOCK");

		assertStock(sku, 3, 3);

		// Every one of those reserves went through the gate and the gate had no opinion. Asserting on
		// the counter rather than on the absence of a key is what distinguishes "the gate fell
		// through" from "the gate was never consulted" -- the second would pass every assertion above
		// while meaning the opposite.
		assertThat(unknownDecisions()).as("reserves that fell through to the database")
				.isGreaterThanOrEqualTo(unknownBefore + 3);
	}

	@Test
	@DisplayName("a release with Redis gone still returns the units in the database")
	void releaseStillWorksWithoutTheGate() {
		String sku = uniqueSku("NORELEASE");
		createStock(sku, 5);
		var held = reserve(uniqueKey("order"), "cust-1", sku, 2).getBody();
		assertStock(sku, 5, 2);

		// RedisAvailabilityGate.release swallows the outage. If it did not, this would fail the
		// release of a hold the database had already given back -- the gate breaking a path it is
		// only meant to accelerate.
		rest.postForObject("/api/v1/inventory/reservations/" + held.reservationKey() + "/release",
				Map.of("reason", "changed their mind"), Map.class);

		assertStock(sku, 5, 0);
	}
}
