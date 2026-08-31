package com.flashcart.inventory.api;

import java.util.List;
import java.util.UUID;

import com.flashcart.inventory.api.dto.AllocationRequest;
import com.flashcart.inventory.api.dto.AllocationResponse;
import com.flashcart.inventory.api.dto.UpdateAllocationRequest;
import com.flashcart.inventory.service.AllocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/allocations")
@Tag(name = "Sale allocations", description = "How much of a SKU a flash sale is permitted to sell")
public class AllocationController {

	private final AllocationService allocations;

	public AllocationController(AllocationService allocations) {
		this.allocations = allocations;
	}

	@GetMapping("/{flashSaleId}")
	@Operation(summary = "Every allocation belonging to one flash sale")
	public List<AllocationResponse> forSale(@PathVariable UUID flashSaleId) {
		return allocations.forSale(flashSaleId).stream().map(AllocationResponse::from).toList();
	}

	@GetMapping("/{flashSaleId}/{sku}")
	@Operation(summary = "One allocation")
	public AllocationResponse get(@PathVariable UUID flashSaleId, @PathVariable String sku) {
		return AllocationResponse.from(allocations.get(flashSaleId, sku));
	}

	@PostMapping
	@Operation(summary = "Ring-fence stock for a flash sale",
			description = "Catalog defines the sale; this is what enforces it. Registered explicitly "
					+ "rather than by calling catalog — Phase 5 replaces this with an event.")
	public ResponseEntity<AllocationResponse> create(@Valid @RequestBody AllocationRequest request) {
		AllocationResponse created = AllocationResponse.from(allocations.create(
				request.flashSaleId(), request.sku(), request.allocatedUnits(),
				request.perCustomerLimit() == null ? 1 : request.perCustomerLimit()));
		return ResponseEntity.status(201).body(created);
	}

	@PutMapping("/{flashSaleId}/{sku}")
	@Operation(summary = "Resize an allocation or change its per-customer cap",
			description = "Refused if it would shrink below what the sale has already held or sold.")
	public AllocationResponse update(@PathVariable UUID flashSaleId, @PathVariable String sku,
			@Valid @RequestBody UpdateAllocationRequest request) {
		return AllocationResponse.from(allocations.update(flashSaleId, sku, request.allocatedUnits(),
				request.perCustomerLimit()));
	}
}
