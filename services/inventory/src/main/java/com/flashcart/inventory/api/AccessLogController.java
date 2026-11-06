package com.flashcart.inventory.api;

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
 * Who read a customer's data in inventory, and when. One per service because there is one
 * {@code operator_access_log} per service: see {@link OperatorAccessLogReader} and ADR 0067.
 *
 * <p>Support's reader and the customer's. The warehouse reaches neither by role: its rules in
 * {@code InventorySecurityConfig} name its own paths, because a wildcard over this prefix would have covered
 * these too. Reading who looked at a customer's data is not warehouse work.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Audit")
@Validated
public class AccessLogController {

	private final OperatorAccessLogReader accessLog;

	public AccessLogController(OperatorAccessLogReader accessLog) {
		this.accessLog = accessLog;
	}

	@GetMapping("/_access-log")
	@Operation(summary = "Who accessed a customer's reservations",
			description = "Support or operator. Newest first, and the newest entry is this request: the access is "
					+ "recorded before the data is returned (ADR 0025). Covers inventory only: the log lives in "
					+ "one database per service.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The recorded accesses, newest first"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify"),
			@ApiResponse(responseCode = "403", description = "OPERATOR_REQUIRED: signed in, but neither support nor an operator")
	})
	public List<OperatorAccessLogReader.Entry> forCustomer(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Parameter(description = "Whose data") @RequestParam @NotBlank String customerId,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return accessLog.forCustomer(authorization, customerId, limit);
	}

	@GetMapping("/_access-log/mine")
	@Operation(summary = "When staff looked at your reservations",
			description = "Signed in. Your own entries only, newest first: when a member of staff looked at your "
					+ "reservations, and at what -- not who. Covers this service only. See ADR 0056.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Staff accesses to your data, newest first"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify")
	})
	public List<OperatorAccessLogReader.CustomerEntry> mine(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return accessLog.mine(authorization, limit);
	}
}
