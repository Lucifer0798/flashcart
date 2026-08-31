package com.flashcart.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.flashcart.inventory.api.dto.AdjustStockRequest;
import com.flashcart.inventory.api.dto.CreateStockRequest;
import com.flashcart.inventory.api.dto.MovementResponse;
import com.flashcart.inventory.api.dto.ReceiveStockRequest;
import com.flashcart.inventory.api.dto.StockResponse;
import com.flashcart.inventory.service.StockService;
import com.flashcart.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/stock")
@Tag(name = "Stock", description = "Warehouse positions and the movement ledger")
public class StockController {

	private static final int MAX_PAGE_SIZE = 200;

	private final StockService stock;

	public StockController(StockService stock) {
		this.stock = stock;
	}

	@GetMapping("/{sku}")
	@Operation(summary = "The position for one SKU")
	public StockResponse get(@PathVariable String sku) {
		return StockResponse.from(stock.get(sku));
	}

	@GetMapping
	@Operation(summary = "Positions for several SKUs at once",
			description = "Batched because the alternative is one call per product tile, which is "
					+ "exactly the pattern that collapses when a sale makes the listing busy.")
	public List<StockResponse> getAll(
			@Parameter(description = "Comma-separated SKUs") @RequestParam List<String> skus) {
		return stock.getAll(skus).stream().map(StockResponse::from).toList();
	}

	@PostMapping
	@Operation(summary = "Start tracking a SKU, optionally with an opening balance")
	public ResponseEntity<StockResponse> create(@Valid @RequestBody CreateStockRequest request) {
		StockResponse created = StockResponse.from(
				stock.create(request.sku(), request.initialQuantity(), request.reason()));
		return ResponseEntity.created(URI.create("/api/v1/inventory/stock/" + created.sku())).body(created);
	}

	@PostMapping("/{sku}/receive")
	@Operation(summary = "Stock arrived", description = "Always additive; use adjust for corrections.")
	public StockResponse receive(@PathVariable String sku, @Valid @RequestBody ReceiveStockRequest request) {
		return StockResponse.from(stock.receive(sku, request.quantity(), request.reason()));
	}

	@PostMapping("/{sku}/adjust")
	@Operation(summary = "Correct a position",
			description = "Signed delta with a mandatory reason. Refused if it would leave fewer units "
					+ "on hand than are already reserved — those are promised to someone.")
	public StockResponse adjust(@PathVariable String sku, @Valid @RequestBody AdjustStockRequest request) {
		return StockResponse.from(stock.adjust(sku, request.delta(), request.reason()));
	}

	@GetMapping("/{sku}/movements")
	@Operation(summary = "The ledger for one SKU, newest first",
			description = "Every change to the position, with the correlation id of the request that "
					+ "caused it.")
	public PageResponse<MovementResponse> movements(@PathVariable String sku,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		Page<MovementResponse> movements = stock
				.movements(sku, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)))
				.map(MovementResponse::from);
		return PageResponse.of(movements.getContent(), movements.getNumber(), movements.getSize(),
				movements.getTotalElements());
	}

	@GetMapping("/movements/reservation/{reservationId}")
	@Operation(summary = "Every movement caused by one reservation, oldest first")
	public List<MovementResponse> movementsForReservation(@PathVariable UUID reservationId) {
		return stock.movementsForReservation(reservationId).stream().map(MovementResponse::from).toList();
	}
}
