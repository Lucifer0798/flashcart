package com.flashcart.inventory.service;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of ADR 0016 that nothing checked: that an <em>unreachable</em> Redis becomes
 * {@link AvailabilityGate.Decision#UNKNOWN} rather than an exception on the reserve path.
 *
 * <p>{@code AvailabilityGateIT.coldCounterFallsThroughToTheDatabase} reaches {@code UNKNOWN} through
 * a missing key. That is the same decision but a different failure: a missing key is Redis answering
 * normally. Nothing exercised the {@code catch (DataAccessException)} arms, which are where the
 * ADR's actual promise lives — "Redis failing degrades the system to Phase 6 behaviour rather than
 * breaking it". A gate that threw instead of swallowing would take every reservation down with it
 * and the suite would have stayed green.
 *
 * <p>No container: the point is a Redis that is not there, and a port nothing is listening on is a
 * more faithful outage than a container that has been stopped — it also keeps this in the fast tier,
 * which is where a check this cheap belongs.
 */
class RedisAvailabilityGateUnreachableTest {

	private static LettuceConnectionFactory connections;
	private static StringRedisTemplate template;
	private static RedisAvailabilityGate gate;

	@BeforeAll
	static void pointAtNothing() throws IOException {
		int deadPort;
		// Bound and immediately released, so the port is real, free, and refusing connections. A
		// hard-coded one could collide with something genuinely listening and make this pass for the
		// wrong reason.
		try (ServerSocket probe = new ServerSocket(0)) {
			deadPort = probe.getLocalPort();
		}
		connections = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration("localhost", deadPort),
				LettuceClientConfiguration.builder()
						// The production posture: give up fast and let PostgreSQL answer.
						.commandTimeout(Duration.ofMillis(250))
						.build());
		connections.afterPropertiesSet();
		template = new StringRedisTemplate(connections);
		template.afterPropertiesSet();
		gate = new RedisAvailabilityGate(template, Duration.ofSeconds(30));
	}

	@AfterAll
	static void closeConnections() {
		connections.destroy();
	}

	@Test
	@DisplayName("the Redis this test points at really is unreachable")
	void redisIsActuallyUnreachable() {
		// Without this the rest of the class could pass against a working Redis, or against a gate
		// that had quietly stopped calling Redis at all, and prove nothing either way.
		assertThatThrownBy(() -> template.hasKey("flashcart:avail:PROBE"))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	@DisplayName("an unreachable Redis is UNKNOWN, so the database still decides")
	void tryAdmitFallsThroughRatherThanThrowing() {
		// Not REFUSED. Refusing here would turn a Redis outage into a sold-out shop while the
		// warehouse was full -- the one failure mode the asymmetry in ADR 0016 exists to prevent.
		assertThat(gate.tryAdmit("UNREACHABLE-SKU", 1)).isEqualTo(AvailabilityGate.Decision.UNKNOWN);
	}

	@Test
	@DisplayName("nothing on the write paths escapes either")
	void theWritePathsSwallowTheOutage() {
		// These run after the database has already been told the truth. An exception escaping any of
		// them would roll back or fail a reservation that PostgreSQL had correctly accepted, which is
		// the gate breaking the system it is only supposed to speed up.
		assertThatCode(() -> gate.release("UNREACHABLE-SKU", 1)).doesNotThrowAnyException();
		assertThatCode(() -> gate.warm("UNREACHABLE-SKU", 5)).doesNotThrowAnyException();
		assertThatCode(() -> gate.invalidate("UNREACHABLE-SKU")).doesNotThrowAnyException();
	}
}
