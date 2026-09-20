package com.flashcart.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Operator access, counted.
 *
 * <p>ADR 0018's organising idea is that the metrics here are a list of the platform's silences, and
 * this was one: an operator read was written to a table nobody queries and a log line, and nothing
 * anywhere would show that it had happened, or that it had started happening more.
 */
class OperatorAccessLogTest {

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final MeterRegistry registry = new SimpleMeterRegistry();
	private final OperatorAccessLog accessLog = new OperatorAccessLog(jdbc, registry);

	private double reads(String action) {
		return registry.find("flashcart.operator.reads").tag("action", action).counter().count();
	}

	private double failures() {
		return registry.counter("flashcart.operator.read.record.failures").count();
	}

	@Test
	@DisplayName("a recorded read is counted under its own action")
	void countsPerAction() {
		accessLog.record("ops", "READ_PAYMENT", "FC-1", "alice");

		assertThat(reads("READ_PAYMENT")).isEqualTo(1);
	}

	@Test
	@DisplayName("actions are counted separately, which is the point of storing the action at all")
	void actionsDoNotShareACounter() {
		accessLog.record("ops", "READ_PAYMENT", "FC-1", "alice");
		accessLog.record("ops", "READ_PAYMENT_LIST", null, "alice");
		accessLog.record("ops", "READ_PAYMENT", "FC-2", "bob");

		// A single total would make "somebody listed every customer" and "somebody opened one
		// payment" the same number, which is the distinction worth having.
		assertThat(reads("READ_PAYMENT")).isEqualTo(2);
		assertThat(reads("READ_PAYMENT_LIST")).isEqualTo(1);
	}

	@Test
	@DisplayName("a read that cannot be recorded is counted and still throws")
	void countsAndRethrowsFailures() {
		doThrow(new DataAccessResourceFailureException("operator_access_log is gone"))
				.when(jdbc).update(any(String.class), any(Object[].class));

		assertThatThrownBy(() -> accessLog.record("ops", "READ_PAYMENT", "FC-1", "alice"))
				.isInstanceOf(DataAccessResourceFailureException.class);

		// Both halves matter. Swallowing it would serve an unrecorded read, which ADR 0025 exists to
		// prevent; not counting it would leave the refusal looking like an unexplained rise in 500s.
		assertThat(failures()).isEqualTo(1);
	}

	@Test
	@DisplayName("and a failed read is not also counted as a read")
	void failuresAreNotCountedAsReads() {
		doThrow(new DataAccessResourceFailureException("gone"))
				.when(jdbc).update(any(String.class), any(Object[].class));

		assertThatThrownBy(() -> accessLog.record("ops", "READ_PAYMENT", "FC-1", "alice"))
				.isInstanceOf(RuntimeException.class);

		assertThat(registry.find("flashcart.operator.reads").counter()).isNull();
	}
}
