package com.flashcart.payment.api;

import com.flashcart.common.security.CallerIdentity;
import com.flashcart.payment.api.dto.PaymentResponse;
import com.flashcart.payment.service.AbandonedRefunds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording that an abandoned refund was paid somewhere else.
 *
 * <p>Not a refund button, and the difference is the point. ADR 0031 rejected an endpoint that moves
 * money, and this moves none: it tells the platform that a person already refunded the customer
 * outside it, so the payment stops claiming the money is owed and the critical alert can resolve. See
 * ADR 0041.
 *
 * <p>Operator-only, by being listed nowhere. {@code OperatorFilter} is default-deny, so an unlisted
 * path needs {@code OPERATOR}. None of the three narrower roles from ADR 0038 fits: {@code SUPPORT}
 * reads customers' data and this silences an alert about money, which is a different job and a more
 * dangerous one to get wrong. On the singular {@code payment} prefix for the reason
 * {@link AccessLogController} gives.
 */
@RestController
@RequestMapping("/api/v1/payment/_refunds")
@Tag(name = "Refunds", description = "Refunds the platform could not make")
public class RefundSettlementController {

	private final AbandonedRefunds abandoned;
	private final CallerIdentity caller;

	public RefundSettlementController(AbandonedRefunds abandoned, CallerIdentity caller) {
		this.abandoned = abandoned;
		this.caller = caller;
	}

	/**
	 * @param reference where the money actually went from: a bank transfer id, the provider console's
	 *                  refund id. Required, because "somebody said it was paid" is not a record
	 */
	public record SettleRequest(@NotBlank @Size(max = 100) String reference) {
	}

	@PostMapping("/{orderNumber}/settled-outside")
	@Operation(summary = "Record an abandoned refund as paid outside the platform",
			description = "Operators only. Moves no money. Allowed only once the retry job has given up "
					+ "on the refund; before that the platform may still ask the provider, and recording "
					+ "a refund made elsewhere could pay the customer twice. Repeating it with the same "
					+ "reference returns the settled payment.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Recorded, now REFUNDED_OUTSIDE"),
			@ApiResponse(responseCode = "400", description = "No reference given"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify"),
			@ApiResponse(responseCode = "403", description = "OPERATOR_REQUIRED"),
			@ApiResponse(responseCode = "404", description = "No payment for that order"),
			@ApiResponse(responseCode = "409", description = "REFUND_NOT_ABANDONED, REFUND_NOT_OWED or "
					+ "REFUND_ALREADY_SETTLED")
	})
	public PaymentResponse settleOutside(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable String orderNumber,
			@Valid @RequestBody SettleRequest request) {
		String operatorId = caller.require(authorization);
		return PaymentResponse.from(abandoned.settleOutside(orderNumber, request.reference(), operatorId));
	}
}
