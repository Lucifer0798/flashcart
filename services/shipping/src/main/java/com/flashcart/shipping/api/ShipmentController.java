package com.flashcart.shipping.api;

import java.util.List;

import com.flashcart.shipping.api.dto.ShipmentResponse;
import com.flashcart.shipping.service.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads, plus the two transitions a warehouse operator drives by hand.
 *
 * <p>There is no endpoint that <em>creates</em> a shipment: that only happens on a command from the
 * order service, once payment has settled. Dispatch and delivery are manual because in a real
 * warehouse they are — a carrier scan, not a decision this platform makes.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipments")
public class ShipmentController {

	private final ShipmentService shipments;

	public ShipmentController(ShipmentService shipments) {
		this.shipments = shipments;
	}

	@GetMapping("/order/{orderNumber}")
	@Operation(summary = "The shipment for an order")
	public ShipmentResponse forOrder(@PathVariable String orderNumber) {
		return ShipmentResponse.from(shipments.getByOrderNumber(orderNumber));
	}

	@GetMapping("/{trackingNumber}")
	@Operation(summary = "Track a shipment")
	public ShipmentResponse track(@PathVariable String trackingNumber) {
		return ShipmentResponse.from(shipments.getByTracking(trackingNumber));
	}

	@GetMapping
	@Operation(summary = "A customer's shipments, newest first")
	public List<ShipmentResponse> forCustomer(@RequestParam String customerId) {
		return shipments.forCustomer(customerId).stream().map(ShipmentResponse::from).toList();
	}

	@PostMapping("/{trackingNumber}/dispatch")
	@Operation(summary = "Hand the consignment to the carrier")
	public ShipmentResponse dispatch(@PathVariable String trackingNumber) {
		return ShipmentResponse.from(shipments.dispatch(trackingNumber));
	}

	@PostMapping("/{trackingNumber}/deliver")
	@Operation(summary = "Record delivery")
	public ShipmentResponse deliver(@PathVariable String trackingNumber) {
		return ShipmentResponse.from(shipments.deliver(trackingNumber));
	}

}
