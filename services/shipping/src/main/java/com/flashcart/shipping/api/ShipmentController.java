package com.flashcart.shipping.api;

import java.util.List;

import com.flashcart.common.error.OperatorRequiredException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.shipping.api.dto.ShipmentResponse;
import com.flashcart.shipping.domain.Shipment;
import com.flashcart.shipping.service.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads, plus the two transitions a warehouse operator drives by hand.
 *
 * <p>There is no endpoint that <em>creates</em> a shipment: that only happens on a command from the
 * order service, once payment has settled. Dispatch and delivery are manual because in a real
 * warehouse they are — a carrier scan, not a decision this platform makes.
 *
 * <h2>Whose parcel</h2>
 *
 * <p>A customer may track their own. Until ADR 0023 these reads required an operator, which meant the
 * one person with the strongest claim to know where a parcel was — the person waiting for it — was the
 * one who could not ask.
 *
 * <p>The split is by verb, and it falls out naturally: <strong>reading</strong> a shipment is the
 * customer's business, <strong>dispatching and delivering</strong> it is the warehouse's. Those two
 * stay operator-only, because a customer marking their own parcel delivered is a customer editing the
 * warehouse's record of reality.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipments")
public class ShipmentController {

	private static final Logger log = LoggerFactory.getLogger(ShipmentController.class);

	private final ShipmentService shipments;
	private final AccessTokens tokens;

	public ShipmentController(ShipmentService shipments, AccessTokens tokens) {
		this.shipments = shipments;
		this.tokens = tokens;
	}

	@GetMapping("/order/{orderNumber}")
	@Operation(summary = "The shipment for one of your orders")
	public ShipmentResponse forOrder(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String orderNumber) {
		return ShipmentResponse.from(mine(authorization, shipments.getByOrderNumber(orderNumber), orderNumber));
	}

	@GetMapping("/{trackingNumber}")
	@Operation(summary = "Track one of your shipments")
	public ShipmentResponse track(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String trackingNumber) {
		return ShipmentResponse.from(mine(authorization, shipments.getByTracking(trackingNumber), trackingNumber));
	}

	@GetMapping
	@Operation(summary = "Your shipments, newest first",
			description = "Whose shipments is decided by the access token. An operator may pass "
					+ "customerId to read somebody else's; anyone else asking for another "
					+ "customer's is refused rather than quietly handed their own.")
	public List<ShipmentResponse> list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(required = false) String customerId) {
		String caller = caller(authorization);
		String subject = customerId == null ? caller : customerId;

		if (!subject.equals(caller) && !isOperator(authorization)) {
			log.info("Refused a shipment listing for a different customer");
			throw new OperatorRequiredException("Only an operator may read another customer's shipments");
		}
		return shipments.forCustomer(subject).stream().map(ShipmentResponse::from).toList();
	}

	@PostMapping("/{trackingNumber}/dispatch")
	@Operation(summary = "Hand the consignment to the carrier",
			description = "Operators only — this is a warehouse action, not a customer's.")
	public ShipmentResponse dispatch(@PathVariable String trackingNumber) {
		return ShipmentResponse.from(shipments.dispatch(trackingNumber));
	}

	@PostMapping("/{trackingNumber}/deliver")
	@Operation(summary = "Record delivery",
			description = "Operators only — a customer marking their own parcel delivered is a "
					+ "customer editing the warehouse's record of reality.")
	public ShipmentResponse deliver(@PathVariable String trackingNumber) {
		return ShipmentResponse.from(shipments.deliver(trackingNumber));
	}

	/**
	 * The shipment, if it is the caller's — or any of them, if the caller is an operator.
	 *
	 * <p>Somebody else's is <strong>404, not 403</strong>, for the reason ADR 0021 gives for orders: a
	 * 403 confirms the identifier exists. That matters more here than anywhere else on the platform,
	 * because a tracking number is printed on a label, read aloud to carriers, and pasted into emails
	 * — it is the most widely handled identifier this system issues.
	 */
	private Shipment mine(String authorization, Shipment shipment, String identifier) {
		if (shipment.getCustomerId().equals(caller(authorization)) || isOperator(authorization)) {
			return shipment;
		}
		log.info("Refused access to a shipment belonging to a different customer");
		throw ResourceNotFoundException.of("Shipment", identifier);
	}

	/**
	 * Who is asking, according to a token this service verified itself.
	 *
	 * <p>The filter in front of this has already rejected anything without a valid token. This is
	 * here because a check performed only somewhere else is a check that vanishes the day the
	 * somewhere else is reconfigured, and compose publishes this service on its own port. See
	 * ADR 0021.
	 */
	private String caller(String authorization) {
		return AccessTokens.bearer(authorization)
				.flatMap(tokens::subject)
				.orElseThrow(() -> new UnauthenticatedException("A valid access token is required"));
	}

	private boolean isOperator(String authorization) {
		return AccessTokens.bearer(authorization).filter(tokens::isOperator).isPresent();
	}
}
