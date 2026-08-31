package com.flashcart.inventory.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundary at which a hold stops holding.
 *
 * <p>Worth testing to the second: this is the difference between a buyer keeping the unit they were
 * promised and it being handed to someone else while their card was being authorised.
 */
class ReservationExpiryTest {

	private static final Instant EXPIRES_AT = Instant.parse("2026-11-27T12:00:00Z");

	private static Reservation reservation(ReservationStatus status) {
		Reservation reservation = new Reservation(UUID.randomUUID(), "order-1", "cust-1", null, EXPIRES_AT);
		reservation.setStatus(status);
		return reservation;
	}

	private static Clock at(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	@Test
	@DisplayName("a hold is still live one second before its deadline")
	void liveBeforeDeadline() {
		assertThat(reservation(ReservationStatus.HELD).isExpired(at(EXPIRES_AT.minusSeconds(1)))).isFalse();
	}

	@Test
	@DisplayName("a hold is expired on the deadline itself, not a second after")
	void expiresOnTheDeadline() {
		// Inclusive on purpose, and it matches the SQL: the reclaim query uses expires_at <= now(),
		// so the boundary must agree or the two paths would disagree for exactly one second.
		assertThat(reservation(ReservationStatus.HELD).isExpired(at(EXPIRES_AT))).isTrue();
	}

	@Test
	@DisplayName("a settled reservation never counts as expired, however long ago its deadline passed")
	void settledReservationsAreNeverExpired() {
		// This is what stops units being returned twice: a committed hold whose deadline has since
		// passed must not look like something to reclaim.
		Clock longAfter = at(EXPIRES_AT.plusSeconds(86_400));
		assertThat(reservation(ReservationStatus.COMMITTED).isExpired(longAfter)).isFalse();
		assertThat(reservation(ReservationStatus.RELEASED).isExpired(longAfter)).isFalse();
		assertThat(reservation(ReservationStatus.EXPIRED).isExpired(longAfter)).isFalse();
	}

	@Test
	@DisplayName("only HELD keeps units out of circulation")
	void onlyHeldIsHolding() {
		assertThat(ReservationStatus.HELD.isHolding()).isTrue();
		assertThat(ReservationStatus.COMMITTED.isHolding()).isFalse();
		assertThat(ReservationStatus.RELEASED.isHolding()).isFalse();
		assertThat(ReservationStatus.EXPIRED.isHolding()).isFalse();
	}

	@Test
	@DisplayName("available is on-hand minus held, never a raw on-hand count")
	void availableExcludesHeldUnits() {
		StockItem item = new StockItem(UUID.randomUUID(), "AUD-01", 100);
		assertThat(item.available()).isEqualTo(100);

		item.setReserved(40);

		// The number a storefront must render. Showing on_hand would promise units already spoken for.
		assertThat(item.available()).isEqualTo(60);
	}

	@Test
	@DisplayName("a sale allocation's remaining units exclude both held and sold")
	void allocationRemainingExcludesHeldAndSold() {
		SaleAllocation allocation = new SaleAllocation(UUID.randomUUID(), UUID.randomUUID(), "AUD-01", 500, 1);

		assertThat(allocation.remainingUnits()).isEqualTo(500);
	}
}
