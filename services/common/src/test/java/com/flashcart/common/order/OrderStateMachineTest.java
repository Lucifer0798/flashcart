package com.flashcart.common.order;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

	@Test
	@DisplayName("the happy path walks end to end")
	void happyPathIsWalkable() {
		OrderStatus[] path = { OrderStatus.CREATED, OrderStatus.RESERVED, OrderStatus.PAYMENT_PENDING,
				OrderStatus.PAID, OrderStatus.FULFILLING, OrderStatus.SHIPPED, OrderStatus.DELIVERED };

		for (int i = 0; i < path.length - 1; i++) {
			assertThat(OrderStateMachine.canTransition(path[i], path[i + 1]))
					.as("%s -> %s", path[i], path[i + 1])
					.isTrue();
		}
	}

	@Test
	@DisplayName("skipping a step is rejected")
	void skippingAStepIsRejected() {
		// The bug this guards: an order jumping straight to PAID without stock ever being held.
		assertThat(OrderStateMachine.canTransition(OrderStatus.CREATED, OrderStatus.PAID)).isFalse();
		assertThat(OrderStateMachine.canTransition(OrderStatus.RESERVED, OrderStatus.SHIPPED)).isFalse();
	}

	@Test
	@DisplayName("a replayed transition out of a state we already left is rejected")
	void replayIsRejected() {
		// At-least-once delivery means a payment callback can arrive twice. The second one finds the
		// order already PAID, and PAID -> PAID is not on the table.
		assertThat(OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.PAID)).isFalse();
		assertThatThrownBy(() -> OrderStateMachine.assertTransition(OrderStatus.PAID, OrderStatus.PAYMENT_PENDING))
				.isInstanceOf(IllegalOrderTransitionException.class)
				.hasMessageContaining("PAID")
				.hasMessageContaining("PAYMENT_PENDING");
	}

	@Test
	@DisplayName("both failure paths lead to inventory being released")
	void failurePathsReleaseInventory() {
		assertThat(OrderStateMachine.canTransition(OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_FAILED)).isTrue();
		assertThat(OrderStateMachine.canTransition(OrderStatus.RESERVED, OrderStatus.RESERVATION_EXPIRED)).isTrue();

		assertThat(OrderStateMachine.releasesInventory(OrderStatus.PAYMENT_FAILED)).isTrue();
		assertThat(OrderStateMachine.releasesInventory(OrderStatus.RESERVATION_EXPIRED)).isTrue();
	}

	@Test
	@DisplayName("a payment timeout does not release inventory, because the charge may still land")
	void timeoutGoesToReconciliationNotRelease() {
		assertThat(OrderStateMachine.releasesInventory(OrderStatus.PAYMENT_TIMEOUT)).isFalse();
		// Reconciliation can settle it either way, which is exactly why it cannot auto-release.
		assertThat(OrderStateMachine.nextStates(OrderStatus.PAYMENT_TIMEOUT))
				.containsExactlyInAnyOrder(OrderStatus.PAID, OrderStatus.CANCELLED);
	}

	@ParameterizedTest
	@EnumSource(OrderStatus.class)
	@DisplayName("every status is either terminal or has somewhere to go")
	void noStatusIsAccidentallyStranded(OrderStatus status) {
		Set<OrderStatus> next = OrderStateMachine.nextStates(status);
		if (status.isTerminal()) {
			assertThat(next).isEmpty();
		}
		else {
			assertThat(next).as("non-terminal %s must have an exit", status).isNotEmpty();
		}
	}
}
