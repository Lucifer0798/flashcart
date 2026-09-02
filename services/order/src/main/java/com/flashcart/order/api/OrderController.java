package com.flashcart.order.api;

import java.net.URI;
import java.util.List;

import com.flashcart.order.api.dto.CancelOrderRequest;
import com.flashcart.order.api.dto.OrderHistoryResponse;
import com.flashcart.order.api.dto.OrderResponse;
import com.flashcart.order.api.dto.PlaceOrderRequest;
import com.flashcart.order.service.OrderService;
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
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Place an order and drive it through the state machine")
public class OrderController {

	private final OrderService orders;

	public OrderController(OrderService orders) {
		this.orders = orders;
	}

	@PostMapping
	@Operation(summary = "Place an order",
			description = "Prices the basket from catalog, then holds the stock in inventory. Returns "
					+ "a RESERVED order on success. Idempotent on idempotencyKey.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Order placed and stock held"),
			@ApiResponse(responseCode = "409", description = "Inventory refused — the body carries its "
					+ "own code (INSUFFICIENT_STOCK, SALE_ALLOCATION_EXHAUSTED, CUSTOMER_LIMIT_EXCEEDED). "
					+ "The order exists and is CANCELLED, with the reason recorded."),
			@ApiResponse(responseCode = "404", description = "A SKU is not in the catalog"),
			@ApiResponse(responseCode = "500", description = "INVENTORY_UNAVAILABLE — the hold's state is "
					+ "unknown. Retry with the same idempotencyKey, which is safe.")
	})
	public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
		List<OrderService.RequestedLine> lines = request.lines().stream()
				.map(line -> new OrderService.RequestedLine(line.sku(), line.quantity()))
				.toList();

		OrderResponse placed = OrderResponse.from(orders.place(request.idempotencyKey(),
				request.customerId(), request.flashSaleId(), lines));

		// 202, not 201. The order exists, but whether it got the stock is not known yet — inventory
		// answers on the bus. Returning 201 would imply a completed outcome the caller has to poll for.
		return ResponseEntity.accepted()
				.location(URI.create("/api/v1/orders/" + placed.orderNumber()))
				.body(placed);
	}

	@GetMapping("/{orderNumber}")
	@Operation(summary = "Fetch an order by its number")
	public OrderResponse get(@PathVariable String orderNumber) {
		return OrderResponse.from(orders.get(orderNumber));
	}

	@GetMapping
	@Operation(summary = "List a customer's orders, newest first")
	public List<OrderResponse> forCustomer(@RequestParam String customerId) {
		return orders.forCustomer(customerId).stream().map(OrderResponse::from).toList();
	}

	@GetMapping("/{orderNumber}/history")
	@Operation(summary = "Every transition this order made, in order",
			description = "The audit trail of the state machine — what support reads to answer "
					+ "'why is this order cancelled'.")
	public List<OrderHistoryResponse> history(@PathVariable String orderNumber) {
		return orders.historyOf(orderNumber).stream().map(OrderHistoryResponse::from).toList();
	}

	@PostMapping("/{orderNumber}/cancel")
	@Operation(summary = "Cancel an order and give its stock back",
			description = "Records the cancellation and asks inventory to release the hold. Refused with "
					+ "409 while a payment is in flight — that has to resolve first.")
	public OrderResponse cancel(@PathVariable String orderNumber,
			@Valid @RequestBody(required = false) CancelOrderRequest request) {
		return OrderResponse.from(orders.cancel(orderNumber, request == null ? null : request.reason()));
	}

	// Note what is deliberately absent: there is no endpoint to request payment, and none to record
	// that one failed. Both were manual in Phase 4 and are now the saga's, driven by events from
	// inventory and payment. Leaving them exposed would give the order lifecycle two drivers — and
	// the one thing worse than a saga is a saga that something else can reach into halfway through.
}
