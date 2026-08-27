package com.flashcart.catalog.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window arithmetic that decides whether the storefront charges list price or sale price.
 *
 * <p>Worth testing to the second: the boundaries are where a stored-and-swept "is live" flag would
 * have gone wrong, and the whole reason the phase is derived instead.
 */
class FlashSalePhaseTest {

	private static final Instant START = Instant.parse("2026-11-27T00:00:00Z");
	private static final Instant END = Instant.parse("2026-11-28T00:00:00Z");

	private static FlashSale sale(FlashSaleStatus status) {
		return new FlashSale(UUID.randomUUID(), "black-friday", "Black Friday", START, END, status);
	}

	private static Clock at(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	@Test
	@DisplayName("before the window opens the sale is UPCOMING")
	void beforeWindow() {
		assertThat(sale(FlashSaleStatus.SCHEDULED).phase(at(START.minusSeconds(1))))
				.isEqualTo(FlashSalePhase.UPCOMING);
	}

	@Test
	@DisplayName("the sale is live on the opening instant itself")
	void startIsInclusive() {
		assertThat(sale(FlashSaleStatus.SCHEDULED).phase(at(START))).isEqualTo(FlashSalePhase.ACTIVE);
	}

	@Test
	@DisplayName("the sale is already over on the closing instant")
	void endIsExclusive() {
		// Half-open on purpose: two back-to-back sales on the same product must never both be live
		// for the instant they touch, or the cheaper-of tie-break would decide the price by accident.
		assertThat(sale(FlashSaleStatus.SCHEDULED).phase(at(END))).isEqualTo(FlashSalePhase.ENDED);
		assertThat(sale(FlashSaleStatus.SCHEDULED).phase(at(END.minusSeconds(1)))).isEqualTo(FlashSalePhase.ACTIVE);
	}

	@Test
	@DisplayName("a DRAFT sale never goes live, however open its window")
	void draftNeverGoesLive() {
		FlashSale draft = sale(FlashSaleStatus.DRAFT);
		assertThat(draft.phase(at(START.plusSeconds(60)))).isEqualTo(FlashSalePhase.DRAFT);
		assertThat(draft.isLive(at(START.plusSeconds(60)))).isFalse();
	}

	@Test
	@DisplayName("cancelling takes a live sale down immediately, with nothing to sweep")
	void cancelledIsImmediatelyDead() {
		FlashSale cancelled = sale(FlashSaleStatus.CANCELLED);
		assertThat(cancelled.phase(at(START.plusSeconds(60)))).isEqualTo(FlashSalePhase.CANCELLED);
		assertThat(cancelled.isLive(at(START.plusSeconds(60)))).isFalse();
	}
}
