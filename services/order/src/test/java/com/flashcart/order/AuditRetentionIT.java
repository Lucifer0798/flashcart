package com.flashcart.order;

import java.util.UUID;

import com.flashcart.common.security.OperatorAccessRetention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long the order service keeps its records of staff reads (ADR 0026, ADR 0069).
 *
 * <p>Every sweep here is the bean the service builds from its own properties, never one constructed
 * by the test. A hand-built sweeper proves the class in {@code flashcart-common} works, which the
 * payment suite already does; what is in question in this module is whether the service has one and
 * whether its setting reaches it. So there are two contexts: this one with nothing configured, and
 * {@link WithAWindow} with a window set.
 *
 * <p>The bean is looked up rather than autowired so that a service without one fails each test with
 * its own reason, instead of failing the whole class while the context starts.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"flashcart.order.reconciler.enabled=false",
		// Nowhere, on purpose. The default is localhost:19092, so on a machine running the compose stack
		// these listeners would join its consumer groups and could take its messages. The module's test
		// config now says the same for every suite (ADR 0070); this stays as the record of where it was
		// first found.
		"spring.kafka.bootstrap-servers=localhost:1",
		// No broker in this suite, and the relay would fail every send.
		"flashcart.outbox.relay.initial-delay=PT1H"
})
class AuditRetentionIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ScheduledTaskHolder scheduled;

	@Autowired
	private JdbcTemplate jdbc;

	/** Both contexts share one database, and the sweep is table-wide, so counts need a known start. */
	@BeforeEach
	void startEmpty() {
		jdbc.update("delete from operator_access_log");
	}

	@Test
	@DisplayName("the sweep is on this service's schedule")
	void theSweepIsScheduled() {
		assertThat(scheduled.getScheduledTasks())
				.extracting(task -> task.getTask().getRunnable().toString())
				.anyMatch(task -> task.contains("OperatorAccessRetention.prune"));
	}

	@Test
	@DisplayName("with nothing configured nothing is deleted, however old it is")
	void keepsEverythingByDefault() {
		String customer = customer("kept");
		recordAged(jdbc, customer, 4000);

		OperatorAccessRetention retention = context.getBean(OperatorAccessRetention.class);

		assertThat(retention.sweep()).isZero();
		assertThat(rows(jdbc, customer)).isEqualTo(1);
	}

	@Nested
	@TestPropertySource(properties = {
			"flashcart.audit.retention=P30D",
			"flashcart.audit.retention-batch-size=2"
	})
	class WithAWindow {

		// Its own, because a field on the enclosing class is filled from the enclosing class's context.
		@Autowired
		private ApplicationContext configured;

		@Test
		@DisplayName("a configured window deletes what is older and keeps what is not, a batch at a time")
		void deletesOnlyWhatIsOlder() {
			String old = customer("old");
			String recent = customer("recent");
			recordAged(jdbc, old, 31);
			recordAged(jdbc, old, 100);
			recordAged(jdbc, old, 4000);
			recordAged(jdbc, recent, 29);
			recordAged(jdbc, recent, 0);

			OperatorAccessRetention retention = configured.getBean(OperatorAccessRetention.class);

			assertThat(retention.sweep()).isEqualTo(2);
			assertThat(rows(jdbc, old)).isEqualTo(1);
			assertThat(retention.sweep()).isEqualTo(1);
			assertThat(retention.sweep()).isZero();
			assertThat(rows(jdbc, old)).isZero();
			assertThat(rows(jdbc, recent)).isEqualTo(2);
		}
	}

	private static String customer(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private static void recordAged(JdbcTemplate jdbc, String customer, int daysOld) {
		jdbc.update("""
				insert into operator_access_log (operator_id, action, resource_id, customer_id, read_at)
				values ('retention-operator', 'READ_ORDER', 'an-order', ?, now() - make_interval(days => ?))
				""", customer, daysOld);
	}

	private static long rows(JdbcTemplate jdbc, String customer) {
		return jdbc.queryForObject("select count(*) from operator_access_log where customer_id = ?", Long.class,
				customer);
	}
}
