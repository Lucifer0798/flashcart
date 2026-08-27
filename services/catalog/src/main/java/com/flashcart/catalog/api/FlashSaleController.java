package com.flashcart.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.flashcart.catalog.api.dto.FlashSaleItemRequest;
import com.flashcart.catalog.api.dto.FlashSaleItemResponse;
import com.flashcart.catalog.api.dto.FlashSaleRequest;
import com.flashcart.catalog.api.dto.FlashSaleResponse;
import com.flashcart.catalog.service.FlashSaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flash-sales")
@Tag(name = "Flash sales")
public class FlashSaleController {

	private final FlashSaleService flashSales;

	public FlashSaleController(FlashSaleService flashSales) {
		this.flashSales = flashSales;
	}

	@GetMapping
	@Operation(summary = "List every flash sale, whatever its phase")
	public List<FlashSaleResponse> list() {
		return flashSales.all();
	}

	@GetMapping("/active")
	@Operation(summary = "Sales that are live right now",
			description = "Derived from the window on every call, so it is never stale.")
	public List<FlashSaleResponse> active() {
		return flashSales.live();
	}

	@GetMapping("/upcoming")
	@Operation(summary = "Scheduled sales whose window has not opened yet")
	public List<FlashSaleResponse> upcoming() {
		return flashSales.upcoming();
	}

	@GetMapping("/{idOrSlug}")
	@Operation(summary = "Fetch one flash sale by id or slug")
	public FlashSaleResponse get(@PathVariable String idOrSlug) {
		return flashSales.get(idOrSlug);
	}

	@PostMapping
	@Operation(summary = "Create a flash sale", description = "Created as DRAFT; it sells nothing until scheduled.")
	public ResponseEntity<FlashSaleResponse> create(@Valid @RequestBody FlashSaleRequest request) {
		FlashSaleResponse created = flashSales.create(request);
		return ResponseEntity.created(URI.create("/api/v1/flash-sales/" + created.id())).body(created);
	}

	@PostMapping("/{id}/items")
	@Operation(summary = "Put a product into a sale",
			description = "Refused with 409 once the sale is live, so prices cannot move under shoppers.")
	public ResponseEntity<FlashSaleItemResponse> addItem(@PathVariable UUID id,
			@Valid @RequestBody FlashSaleItemRequest request) {
		return ResponseEntity.status(201).body(flashSales.addItem(id, request));
	}

	@DeleteMapping("/{id}/items/{itemId}")
	@Operation(summary = "Take a product back out of a sale that has not started")
	public ResponseEntity<Void> removeItem(@PathVariable UUID id, @PathVariable UUID itemId) {
		flashSales.removeItem(id, itemId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/schedule")
	@Operation(summary = "Approve a sale to go live when its window opens")
	public FlashSaleResponse schedule(@PathVariable UUID id) {
		return flashSales.schedule(id);
	}

	@PostMapping("/{id}/cancel")
	@Operation(summary = "Pull a sale", description = "Prices revert to list on the next read; nothing to sweep.")
	public FlashSaleResponse cancel(@PathVariable UUID id) {
		return flashSales.cancel(id);
	}
}
