package com.flashcart.payment.api;

import java.util.List;
import java.util.UUID;

import com.flashcart.common.error.OperatorRequiredException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.error.UnauthenticatedException;
import com.flashcart.common.security.AccessTokens;
import com.flashcart.payment.api.dto.PaymentResponse;
import com.flashcart.payment.domain.Payment;
import com.flashcart.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, deliberately.
 *
 * <p>There is no endpoint here that charges a card: payment is initiated by a command on the bus and
 * nowhere else, so the one operation that moves money has exactly one entry point.
 *
 * <h2>Whose payment</h2>
 *
 * <p>A customer may read their own. Until now these endpoints required an operator, which was safe
 * and wrong: a shopper could not see what they had been charged for their own order. ADR 0022 said
 * so at the time and called fixing it the obvious next piece.
 *
 * <p>Ownership is decided here rather than by asking the order service, because a payment row already
 * carries the {@code customerId} that arrived on {@code RequestPayment} — which the order service
 * took from a token it verified, not from anything a client claimed. Asking would be a synchronous
 * call on a read path to re-derive a value already stored, and a new way for this service to be
 * unavailable whenever the order service is.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "What was charged, and what the provider said")
public class PaymentController {

	private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

	private final PaymentService payments;
	private final AccessTokens tokens;

	public PaymentController(PaymentService payments, AccessTokens tokens) {
		this.payments = payments;
		this.tokens = tokens;
	}

	@GetMapping("/{paymentId}")
	@Operation(summary = "One of your payment attempts")
	public PaymentResponse get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID paymentId) {
		return PaymentResponse.from(mine(authorization, payments.get(paymentId), paymentId.toString()));
	}

	@GetMapping("/order/{orderNumber}")
	@Operation(summary = "The payment attempt for one of your orders")
	public PaymentResponse forOrder(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String orderNumber) {
		return PaymentResponse.from(mine(authorization, payments.getByOrderNumber(orderNumber), orderNumber));
	}

	@GetMapping
	@Operation(summary = "Your payment attempts, newest first",
			description = "Whose payments is decided by the access token. An operator may pass "
					+ "customerId to read somebody else's; anyone else asking for another "
					+ "customer's is refused rather than quietly handed their own.")
	public List<PaymentResponse> list(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(required = false) String customerId) {
		String caller = caller(authorization);
		String subject = customerId == null ? caller : customerId;

		// Refused, not quietly narrowed to the caller's own rows. Answering a request for somebody
		// else's data with your own reads as success: whoever wrote the call believes it worked, and
		// discovers what it actually returned much later, somewhere less convenient.
		if (!subject.equals(caller) && !isOperator(authorization)) {
			log.info("Refused a payment listing for a different customer");
			throw new OperatorRequiredException("Only an operator may read another customer's payments");
		}
		return payments.forCustomer(subject).stream().map(PaymentResponse::from).toList();
	}

	/**
	 * The payment, if it is the caller's — or any of them, if the caller is an operator.
	 *
	 * <p>Somebody else's is <strong>404, not 403</strong>, for the reason ADR 0021 gives for orders: a
	 * 403 confirms the identifier exists, which turns this into an oracle for anybody enumerating
	 * them. Order numbers are short and guessable, and one of them is the key to this row.
	 */
	private Payment mine(String authorization, Payment payment, String identifier) {
		if (payment.getCustomerId().equals(caller(authorization)) || isOperator(authorization)) {
			return payment;
		}
		log.info("Refused access to a payment belonging to a different customer");
		throw ResourceNotFoundException.of("Payment", identifier);
	}

	/**
	 * Who is asking, according to a token this service verified itself.
	 *
	 * <p>The filter in front of this has already rejected anything without a valid token, so in the
	 * normal path this passes trivially. It is here because being wrong about it means handing one
	 * customer another customer's charges, and a check performed only somewhere else is a check that
	 * vanishes the day the somewhere else is reconfigured. See ADR 0021.
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
