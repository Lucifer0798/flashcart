package com.flashcart.inventory.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.inventory.service.ReservationService.RequestedLine;

/**
 * Puts the lines of a reservation request into the one order it is safe to take them in.
 *
 * <p>Extracted from {@link ReservationService} because the sort is not cosmetic and deserves a test
 * that says so. Two multi-line reservations touching SKUs {@code A} and {@code B} in opposite orders
 * will deadlock in PostgreSQL — each transaction holds the row the other is waiting for, and one of
 * them is killed by the deadlock detector after a delay. Taking rows in a globally consistent order
 * makes that impossible, and it costs exactly one sort per request.
 */
final class ReservationLines {

	private ReservationLines() {
	}

	/**
	 * Upper-cases every SKU, rejects a repeated SKU, and sorts.
	 *
	 * @throws BadRequestException when the request is empty or names the same SKU twice
	 */
	static List<RequestedLine> normalise(List<RequestedLine> requested) {
		if (requested == null || requested.isEmpty()) {
			throw new BadRequestException("A reservation must have at least one line");
		}

		List<RequestedLine> lines = requested.stream()
				.map(line -> new RequestedLine(line.sku().trim().toUpperCase(Locale.ROOT), line.quantity()))
				.sorted(Comparator.comparing(RequestedLine::sku))
				.toList();

		// Rejected rather than summed: two lines for the same SKU almost always means the caller
		// built the basket wrong, and silently combining them would hide that until someone
		// wondered why they had been charged for one line and shipped two.
		for (int i = 1; i < lines.size(); i++) {
			if (lines.get(i).sku().equals(lines.get(i - 1).sku())) {
				throw new BadRequestException(
						"SKU %s appears more than once; combine the quantities".formatted(lines.get(i).sku()));
			}
		}
		for (RequestedLine line : lines) {
			if (line.quantity() <= 0) {
				throw new BadRequestException("Quantity for %s must be positive".formatted(line.sku()));
			}
		}
		return lines;
	}
}
