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
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.order.domain.Order;
import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
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

	private static final Logger log = LoggerFactory.getLogger(OrderController.class);

	private final OrderService orders;
	private final AccessTokens tokens;

	public OrderController(OrderService orders, AccessTokens tokens) {
		this.orders = orders;
		this.tokens = tokens;
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
	public ResponseEntity<OrderResponse> place(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody PlaceOrderRequest request) {

		List<OrderService.RequestedLine> lines = request.lines().stream()
				.map(line -> new OrderService.RequestedLine(line.sku(), line.quantity()))
				.toList();

		OrderResponse placed = OrderResponse.from(orders.place(request.idempotencyKey(),
				caller(authorization), request.flashSaleId(), lines));

		// 202, not 201. The order exists, but whether it got the stock is not known yet — inventory
		// answers on the bus. Returning 201 would imply a completed outcome the caller has to poll for.
		return ResponseEntity.accepted()
				.location(URI.create("/api/v1/orders/" + placed.orderNumber()))
				.body(placed);
	}

	@GetMapping("/{orderNumber}")
	@Operation(summary = "Fetch one of your orders by its number")
	public OrderResponse get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String orderNumber) {
		return OrderResponse.from(mine(authorization, orderNumber));
	}

	@GetMapping
	@Operation(summary = "List your orders, newest first",
			description = "Whose orders is decided by the access token. There is no customerId "
					+ "parameter, because a parameter naming whose data to return is a parameter "
					+ "somebody will change to somebody else's.")
	public List<OrderResponse> mine(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return orders.forCustomer(caller(authorization)).stream().map(OrderResponse::from).toList();
	}

	@GetMapping("/{orderNumber}/history")
	@Operation(summary = "Every transition this order made, in order",
			description = "The audit trail of the state machine — what support reads to answer "
					+ "'why is this order cancelled'.")
	public List<OrderHistoryResponse> history(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String orderNumber) {
		mine(authorization, orderNumber);
		return orders.historyOf(orderNumber).stream().map(OrderHistoryResponse::from).toList();
	}

	@PostMapping("/{orderNumber}/cancel")
	@Operation(summary = "Cancel an order and give its stock back",
			description = "Records the cancellation and asks inventory to release the hold. Refused with "
					+ "409 while a payment is in flight — that has to resolve first.")
	public OrderResponse cancel(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String orderNumber,
			@Valid @RequestBody(required = false) CancelOrderRequest request) {
		mine(authorization, orderNumber);
		return OrderResponse.from(orders.cancel(orderNumber, request == null ? null : request.reason()));
	}

	// Note what is deliberately absent: there is no endpoint to request payment, and none to record
	// that one failed. Both were manual in Phase 4 and are now the saga's, driven by events from
	// inventory and payment. Leaving them exposed would give the order lifecycle two drivers — and
	// the one thing worse than a saga is a saga that something else can reach into halfway through.

	/**
	 * Who is asking, according to a token this service verified itself.
	 *
	 * <p>The gateway already refused anything without a valid token, so in the normal path this check
	 * passes trivially. It is here because the gateway is not a boundary: compose publishes this
	 * service on 18082 and the README tells people to use those ports, so a client can simply skip the
	 * edge. Trusting an injected header would be an authentication system with an opt-out.
	 *
	 * <p>This is the one service where being wrong about the customer means selling a stranger's order
	 * to somebody, so it verifies rather than inherits. See ADR 0021.
	 */
	/**
	 * The order, if it belongs to the caller.
	 *
	 * <p>Somebody else's order is reported as <strong>404, not 403</strong>. A 403 confirms that the
	 * order number exists, which turns this endpoint into an oracle: order numbers are short and
	 * guessable, and "this one is real but not yours" is exactly the answer somebody enumerating them
	 * wants. The same reasoning as sign-in refusing to distinguish an unknown email from a wrong
	 * password.
	 *
	 * <p>Closing this mattered as much as taking customerId out of the request body. Placing an order
	 * as somebody else and cancelling somebody else's order are the same hole seen from two ends, and
	 * fixing only the first would have been half a fix -- which this project has shipped before.
	 */
	private Order mine(String authorization, String orderNumber) {
		String caller = caller(authorization);
		Order order = orders.get(orderNumber);
		if (!order.getCustomerId().equals(caller)) {
			log.info("Refused access to order {} for a different customer", orderNumber);
			throw ResourceNotFoundException.of("Order", orderNumber);
		}
		return order;
	}

	private String caller(String authorization) {
		return AccessTokens.bearer(authorization)
				.flatMap(tokens::subject)
				.orElseThrow(() -> new UnauthenticatedException("A valid access token is required"));
	}
}
