package com.flashcart.user.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.flashcart.common.security.CallerIdentity;
import com.flashcart.common.security.OperatorAccessLogReader;
import com.flashcart.common.web.DataExport;
import com.flashcart.user.api.dto.UserDtos.UserResponse;
import com.flashcart.user.service.AccountExport;
import com.flashcart.user.service.UserService;
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
@RequestMapping("/api/v1/user")
@Tag(name = "Your data")
public class ExportController {

	private final CallerIdentity caller;
	private final UserService users;
	private final AccountExport account;
	private final OperatorAccessLogReader accessLog;

	public ExportController(CallerIdentity caller, UserService users, AccountExport account,
			OperatorAccessLogReader accessLog) {
		this.caller = caller;
		this.users = users;
		this.account = account;
		this.accessLog = accessLog;
	}

	@GetMapping("/_export/mine")
	@Operation(summary = "Download what this service holds about you",
			description = "Signed in. Your profile and addresses, the emails we sent you, your sessions and password "
					+ "resets, any staff roles, and when staff looked. Never a password or token, nor their hashes. "
					+ "Whose is decided by the token; the request names nobody. A closed account gets 401: it has "
					+ "nothing identifying left to export. See ADR 0066.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "A JSON file"),
			@ApiResponse(responseCode = "401", description = "No token, one that does not verify, or a closed account")
	})
	public ResponseEntity<Map<String, Object>> mine(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		String customerId = caller.require(authorization);
		UUID id = UUID.fromString(customerId);
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("profile", UserResponse.of(users.require(id)));
		sections.put("emails", account.emails(id));
		sections.put("sessions", account.sessions(id));
		sections.put("passwordResets", account.passwordResets(id));
		sections.put("roleChanges", account.roleChanges(id));
		sections.put("staffAccess", accessLog.mine(authorization, DataExport.ALL_STAFF_READS));
		return DataExport.of("user", customerId, sections);
	}
}
