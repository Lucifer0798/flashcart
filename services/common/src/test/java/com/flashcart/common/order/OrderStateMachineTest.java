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

	// --- cancelling after the money has moved -------------------------------------------------------

	@Test
	@DisplayName("a paid order cannot walk straight to cancelled")
	void cancellingAPaidOrderIsNotOneStep() {
		// Both of these edges existed, and taking either compensated nothing: the capture stayed
		// with the platform and the committed units did not come back, so the customer was left with
		// neither. Nothing asserted them, which is how they survived.
		assertThat(OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.CANCELLED)).isFalse();
		assertThat(OrderStateMachine.canTransition(OrderStatus.FULFILLING, OrderStatus.CANCELLED))
				.isFalse();
		assertThat(OrderStateMachine.canTransition(OrderStatus.SHIPPED, OrderStatus.CANCELLED)).isFalse();
	}

	@Test
	@DisplayName("it goes through a request instead, which shipping answers either way")
	void cancellationIsRequestedAndAnswered() {
		assertThat(OrderStateMachine.canTransition(OrderStatus.SHIPPED,
				OrderStatus.CANCELLATION_REQUESTED)).isTrue();

		// Both answers, and the refusal matters as much as the success: without SHIPPED here, an
		// order whose parcel had already left would sit in CANCELLATION_REQUESTED with no exit.
		assertThat(OrderStateMachine.nextStates(OrderStatus.CANCELLATION_REQUESTED))
				.containsExactlyInAnyOrder(OrderStatus.CANCELLED, OrderStatus.SHIPPED);
	}

	@Test
	@DisplayName("a delivered order is not cancellable, because that is a return")
	void deliveredIsNotCancellable() {
		assertThat(OrderStateMachine.canTransition(OrderStatus.DELIVERED,
				OrderStatus.CANCELLATION_REQUESTED)).isFalse();
		assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
	}

	@Test
	@DisplayName("cancelling before payment is still one step, because nothing was taken")
	void cancellingBeforePaymentIsUnchanged() {
		assertThat(OrderStateMachine.canTransition(OrderStatus.CREATED, OrderStatus.CANCELLED)).isTrue();
		assertThat(OrderStateMachine.canTransition(OrderStatus.RESERVED, OrderStatus.CANCELLED)).isTrue();
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
