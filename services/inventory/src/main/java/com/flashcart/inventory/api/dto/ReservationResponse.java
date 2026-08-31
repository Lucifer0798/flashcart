package com.flashcart.inventory.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.inventory.domain.Reservation;
import com.flashcart.inventory.domain.ReservationStatus;

/**
 * @param expiresAt when the hold lapses if nothing else happens first — clients render a countdown
 *                  from this, and the order service uses it to set its own timeout
 */
public record ReservationResponse(
		UUID id,
		String reservationKey,
		String customerId,
		UUID flashSaleId,
		ReservationStatus status,
		Instant expiresAt,
		Instant committedAt,
		Instant releasedAt,
		List<Line> lines,
		Instant createdAt) {

	public record Line(String sku, int quantity) {
	}

	public static ReservationResponse from(Reservation reservation) {
		List<Line> lines = reservation.getLines().stream()
				.map(line -> new Line(line.getSku(), line.getQuantity()))
				.toList();
		return new ReservationResponse(reservation.getId(), reservation.getReservationKey(),
				reservation.getCustomerId(), reservation.getFlashSaleId(), reservation.getStatus(),
				reservation.getExpiresAt(), reservation.getCommittedAt(), reservation.getReleasedAt(),
				lines, reservation.getCreatedAt());
	}
}
