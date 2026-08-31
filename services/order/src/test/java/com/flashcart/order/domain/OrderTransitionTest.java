package com.flashcart.order.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.flashcart.common.order.IllegalOrderTransitionException;
import com.flashcart.common.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregate's own guarantees, without a database or a network in the way.
 *
 * <p>{@code OrderStateMachineTest} in flashcart-common already proves the transition table itself.
 * What is tested here is that the aggregate actually consults it — that no path exists to move an
 * order's status without going through the machine.
 */
class OrderTransitionTest {

	private static final Instant EXPIRES_AT = Instant.parse("2026-11-27T12:00:00Z");

	private static Order order() {
		return new Order(UUID.randomUUID(), "FC-TEST0001", "cust-1", null, "USD", "idem-1");
	}

	private static Clock at(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	@Test
	@DisplayName("a new order starts CREATED and holds nothing")
	void startsCreated() {
		Order order = order();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.holdsInventory()).isFalse();
		// The reservation key is the order id, which is what makes inventory's reserve idempotent
		// for free — and therefore what makes a timed-out reserve safe to retry.
		assertThat(order.getReservationKey()).isEqualTo(order.getId().toString());
	}

	@Test
	@DisplayName("the checkout path is walkable")
	void checkoutPathIsWalkable() {
		Order order = order();

		order.transitionTo(OrderStatus.RESERVED, "held", null);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);

		order.transitionTo(OrderStatus.PAYMENT_PENDING, "payment requested", null);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	@Test
	@DisplayName("skipping the reservation is impossible, so an order cannot be paid for stock it never held")
	void cannotSkipReservation() {
		assertThatThrownBy(() -> order().transitionTo(OrderStatus.PAID, "wishful", null))
				.isInstanceOf(IllegalOrderTransitionException.class);
	}

	@Test
	@DisplayName("a duplicate event is rejected by the machine rather than by the caller remembering")
	void duplicateTransitionIsRejected() {
		Order order = order();
		order.transitionTo(OrderStatus.RESERVED, "held", null);

		// At-least-once delivery means a reserve confirmation can arrive twice. The second one finds
		// the order already RESERVED, and RESERVED -> RESERVED is not an edge.
		assertThatThrownBy(() -> order.transitionTo(OrderStatus.RESERVED, "held again", null))
				.isInstanceOf(IllegalOrderTransitionException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
	}

	@Test
	@DisplayName("every transition produces a history entry, so the trail cannot be forgotten")
	void transitionProducesHistory() {
		Order order = order();

		OrderStatusChange change = order.transitionTo(OrderStatus.RESERVED, "held by inventory", "corr-1");

		assertThat(change.getFromStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(change.getToStatus()).isEqualTo(OrderStatus.RESERVED);
		assertThat(change.getReason()).isEqualTo("held by inventory");
		assertThat(change.getCorrelationId()).isEqualTo("corr-1");
		assertThat(change.getOrderId()).isEqualTo(order.getId());
	}

	@Test
	@DisplayName("cancelling records why, because 'why can I not buy this' is a real support question")
	void cancellingRecordsTheReason() {
		Order order = order();
		order.transitionTo(OrderStatus.RESERVED, "held", null);

		order.transitionTo(OrderStatus.CANCELLED, "payment declined", null);

		assertThat(order.getCancellationReason()).isEqualTo("payment declined");
	}

	@Test
	@DisplayName("an order holds inventory only while RESERVED or PAYMENT_PENDING")
	void holdsInventoryOnlyWhileItReallyDoes() {
		Order order = order();
		assertThat(order.holdsInventory()).isFalse();

		order.transitionTo(OrderStatus.RESERVED, null, null);
		assertThat(order.holdsInventory()).isTrue();

		order.transitionTo(OrderStatus.PAYMENT_PENDING, null, null);
		assertThat(order.holdsInventory()).isTrue();

		// Cancelling is what gives the stock back; after it there is nothing left to compensate,
		// and a second release would be wrong rather than merely redundant.
		order.transitionTo(OrderStatus.PAYMENT_FAILED, "declined", null);
		order.transitionTo(OrderStatus.CANCELLED, "declined", null);
		assertThat(order.holdsInventory()).isFalse();
	}

	@Test
	@DisplayName("an order with a payment in flight cannot simply be cancelled")
	void paymentPendingCannotBeCancelledDirectly() {
		Order order = order();
		order.transitionTo(OrderStatus.RESERVED, null, null);
		order.transitionTo(OrderStatus.PAYMENT_PENDING, null, null);

		// There is deliberately no PAYMENT_PENDING -> CANCELLED edge. A charge is in flight, and
		// walking away from it would mean releasing stock for an order that might still be paid for.
		// The payment has to resolve first — to PAID, PAYMENT_FAILED or PAYMENT_TIMEOUT.
		assertThatThrownBy(() -> order.transitionTo(OrderStatus.CANCELLED, "impatient", null))
				.isInstanceOf(IllegalOrderTransitionException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	@Test
	@DisplayName("only a RESERVED order past its deadline counts as expired")
	void expiryNeedsBothStatusAndDeadline() {
		Order order = order();
		order.setReservationExpiresAt(EXPIRES_AT);

		// CREATED with a deadline is not expired — nothing is being held yet.
		assertThat(order.hasExpiredReservation(at(EXPIRES_AT.plusSeconds(60)))).isFalse();

		order.transitionTo(OrderStatus.RESERVED, null, null);
		assertThat(order.hasExpiredReservation(at(EXPIRES_AT.minusSeconds(1)))).isFalse();
		assertThat(order.hasExpiredReservation(at(EXPIRES_AT))).isTrue();

		// And once payment is in flight it is no longer the reconciler's business: reclaiming stock
		// from underneath a charge that might yet succeed is the PAYMENT_TIMEOUT mistake.
		order.transitionTo(OrderStatus.PAYMENT_PENDING, null, null);
		assertThat(order.hasExpiredReservation(at(EXPIRES_AT.plusSeconds(3600)))).isFalse();
	}

	@Test
	@DisplayName("totals are computed from the lines, never taken from a caller")
	void totalsComeFromTheLines() {
		Order order = order();

		order.addLine(new OrderLine(UUID.randomUUID(), "AUD-01", "Headphones", 2, new BigDecimal("179.00")));
		order.addLine(new OrderLine(UUID.randomUUID(), "WEA-01", "Watch", 1, new BigDecimal("299.00")));

		assertThat(order.getSubtotal()).isEqualByComparingTo("657.00");
		assertThat(order.getTotal()).isEqualByComparingTo("657.00");
		assertThat(order.getLines()).hasSize(2);
		assertThat(order.getLines().get(0).getLineTotal()).isEqualByComparingTo("358.00");
	}
}
