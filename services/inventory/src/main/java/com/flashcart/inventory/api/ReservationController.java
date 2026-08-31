package com.flashcart.inventory.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.flashcart.inventory.api.dto.ReleaseRequest;
import com.flashcart.inventory.api.dto.ReservationResponse;
import com.flashcart.inventory.api.dto.ReserveRequest;
import com.flashcart.inventory.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/reservations")
@Tag(name = "Reservations", description = "Hold stock, then commit or release it")
public class ReservationController {

	private final ReservationService reservations;

	public ReservationController(ReservationService reservations) {
		this.reservations = reservations;
	}

	@PostMapping
	@Operation(summary = "Hold stock for an order",
			description = "All-or-nothing across lines. Idempotent on reservationKey: retrying returns "
					+ "the original hold rather than taking a second one.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Stock held"),
			@ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK, "
					+ "SALE_ALLOCATION_EXHAUSTED or CUSTOMER_LIMIT_EXCEEDED — branch on the code, "
					+ "since all three read as 'sold out' to a shopper but mean different things"),
			@ApiResponse(responseCode = "404", description = "A SKU is not tracked by inventory")
	})
	public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
		List<ReservationService.RequestedLine> lines = request.lines().stream()
				.map(line -> new ReservationService.RequestedLine(line.sku(), line.quantity()))
				.toList();

		ReservationResponse response = ReservationResponse.from(reservations.reserve(
				request.reservationKey(),
				request.customerId(),
				request.flashSaleId(),
				lines,
				request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds())));

		return ResponseEntity
				.created(URI.create("/api/v1/inventory/reservations/" + response.reservationKey()))
				.body(response);
	}

	@GetMapping("/{reservationKey}")
	@Operation(summary = "Fetch a reservation by its key")
	public ReservationResponse get(@PathVariable String reservationKey) {
		return ReservationResponse.from(reservations.get(reservationKey));
	}

	@GetMapping
	@Operation(summary = "List a customer's reservations, newest first")
	public List<ReservationResponse> forCustomer(@RequestParam String customerId) {
		return reservations.forCustomer(customerId).stream().map(ReservationResponse::from).toList();
	}

	@PostMapping("/{reservationKey}/commit")
	@Operation(summary = "Turn a hold into a sale",
			description = "Called when payment lands. Idempotent. Refused with 409 if the hold already "
					+ "expired — those units may since have been sold to someone else, so the caller "
					+ "must reconcile rather than confirm an order that cannot be filled.")
	public ReservationResponse commit(@PathVariable String reservationKey) {
		return ReservationResponse.from(reservations.commit(reservationKey));
	}

	@PostMapping("/{reservationKey}/release")
	@Operation(summary = "Give a hold back early",
			description = "For an abandoned basket, a declined card, a cancelled order. Idempotent, and "
					+ "a no-op on a hold that already expired on its own.")
	public ReservationResponse release(@PathVariable String reservationKey,
			@Valid @RequestBody(required = false) ReleaseRequest request) {
		return ReservationResponse.from(
				reservations.release(reservationKey, request == null ? null : request.reason()));
	}
}
