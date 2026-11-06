package com.flashcart.shipping.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.flashcart.common.security.CallerIdentity;
import com.flashcart.common.security.OperatorAccessLogReader;
import com.flashcart.common.web.DataExport;
import com.flashcart.shipping.api.dto.ShipmentResponse;
import com.flashcart.shipping.service.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What this service holds about the caller, as a file to keep. See ADR 0066. */
@RestController
@RequestMapping("/api/v1/shipping")
@Tag(name = "Your data")
public class ExportController {

	private final CallerIdentity caller;
	private final ShipmentService shipments;
	private final OperatorAccessLogReader accessLog;

	public ExportController(CallerIdentity caller, ShipmentService shipments, OperatorAccessLogReader accessLog) {
		this.caller = caller;
		this.shipments = shipments;
		this.accessLog = accessLog;
	}

	@GetMapping("/_export/mine")
	@Operation(summary = "Download what this service holds about you",
			description = "Signed in. Your parcels, and when staff looked at them. Whose is decided by the token; the "
					+ "request names nobody. See ADR 0066.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "A JSON file"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify")
	})
	public ResponseEntity<Map<String, Object>> mine(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		String customerId = caller.require(authorization);
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("shipments", shipments.forCustomer(customerId).stream().map(ShipmentResponse::from).toList());
		sections.put("staffAccess", accessLog.mine(authorization, DataExport.ALL_STAFF_READS));
		return DataExport.of("shipping", customerId, sections);
	}
}
