package com.flashcart.inventory.service;

import java.util.List;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.inventory.service.ReservationService.RequestedLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationLinesTest {

	private static RequestedLine line(String sku, int quantity) {
		return new RequestedLine(sku, quantity);
	}

	@Test
	@DisplayName("lines come back sorted by SKU, whatever order the caller sent them")
	void sortsBySku() {
		// Not cosmetic. Two reservations touching WEA-01 and AUD-01 in opposite orders deadlock in
		// PostgreSQL, each holding the row the other wants. A global order makes that impossible.
		List<RequestedLine> normalised = ReservationLines.normalise(List.of(
				line("WEA-01", 1), line("AUD-01", 2), line("HOM-01", 3)));

		assertThat(normalised).extracting(RequestedLine::sku)
				.containsExactly("AUD-01", "HOM-01", "WEA-01");
	}

	@Test
	@DisplayName("the order is the same regardless of the order it arrived in")
	void sortIsStableAcrossCallers() {
		List<String> first = ReservationLines.normalise(List.of(line("B-2", 1), line("A-1", 1)))
				.stream().map(RequestedLine::sku).toList();
		List<String> second = ReservationLines.normalise(List.of(line("A-1", 1), line("B-2", 1)))
				.stream().map(RequestedLine::sku).toList();

		// The whole point: two concurrent callers with the same basket take rows in the same order.
		assertThat(first).isEqualTo(second);
	}

	@Test
	@DisplayName("SKUs are upper-cased and trimmed")
	void normalisesSkuCasing() {
		assertThat(ReservationLines.normalise(List.of(line("  aud-hp-001 ", 1))))
				.singleElement()
				.extracting(RequestedLine::sku)
				.isEqualTo("AUD-HP-001");
	}

	@Test
	@DisplayName("a repeated SKU is rejected rather than silently summed")
	void rejectsDuplicateSku() {
		// Silently combining would hide a caller's basket bug until someone was charged for one
		// unit and shipped two.
		assertThatThrownBy(() -> ReservationLines.normalise(List.of(line("AUD-01", 1), line("aud-01", 2))))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("AUD-01");
	}

	@Test
	@DisplayName("an empty request is rejected")
	void rejectsEmptyRequest() {
		assertThatThrownBy(() -> ReservationLines.normalise(List.of()))
				.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> ReservationLines.normalise(null))
				.isInstanceOf(BadRequestException.class);
	}

	@Test
	@DisplayName("a non-positive quantity is rejected")
	void rejectsNonPositiveQuantity() {
		assertThatThrownBy(() -> ReservationLines.normalise(List.of(line("AUD-01", 0))))
				.isInstanceOf(BadRequestException.class);
	}
}
