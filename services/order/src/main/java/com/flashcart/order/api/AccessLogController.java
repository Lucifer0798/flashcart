package com.flashcart.order.api;

import java.util.List;

import com.flashcart.common.security.OperatorAccessLogReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who read a customer's data in orders, and when.
 *
 * <p>The reader ADR 0025 left out and eight later records kept naming. One per service because there
 * is one {@code operator_access_log} per service — see {@link OperatorAccessLogReader}.
 *
 * <p><strong>This service has no {@code OperatorFilter}.</strong> Unlike payment and shipping it is
 * not default-deny; every endpoint authenticates for itself, so this one guards itself in
 * {@link OperatorAccessLogReader#forCustomer} and there is no filter behind it if that check were
 * ever removed. That asymmetry is not a decision anybody recorded — ADR 0022 added the filter to the
 * two services that had no authentication at all, and the order service already had some. ADR 0034
 * names it; the test for a non-operator being refused is the only thing holding it.
 */
@RestController
@RequestMapping("/api/v1/order")
@Tag(name = "Audit")
@Validated
public class AccessLogController {

	private final OperatorAccessLogReader accessLog;

	public AccessLogController(OperatorAccessLogReader accessLog) {
		this.accessLog = accessLog;
	}

	@GetMapping("/_access-log")
	@Operation(summary = "Who accessed a customer's orders data",
			description = "Operators only. Newest first, and the newest entry is this request: the "
					+ "access is recorded before the data is returned, which is the property ADR 0025 "
					+ "exists for. Covers orders only — the log lives in one database per service.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The recorded accesses, newest first"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify"),
			@ApiResponse(responseCode = "403", description = "OPERATOR_REQUIRED — signed in, but not an operator")
	})
	public List<OperatorAccessLogReader.Entry> forCustomer(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Parameter(description = "Whose data") @RequestParam @NotBlank String customerId,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return accessLog.forCustomer(authorization, customerId, limit);
	}
}
