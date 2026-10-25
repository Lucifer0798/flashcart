package com.flashcart.user.api;

import java.util.List;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.OperatorRequiredException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.security.CallerIdentity;
import com.flashcart.common.security.OperatorAccessLog;
import com.flashcart.common.security.OperatorAccessLogReader;
import com.flashcart.user.notice.NoticeHistory;
import com.flashcart.user.repository.UserRepository;
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
 * Support's reads: what a customer was emailed, and who has looked. See ADR 0051.
 *
 * <p>On the singular {@code user} prefix beside {@code _info}, as payment's audit reader is, so no rule
 * written for the plural {@code users} paths can open it by accident.
 */
@RestController
@RequestMapping("/api/v1/user")
@Tag(name = "Support")
@Validated
public class SupportController {

	/** What a member of staff reading a customer's emails is recorded as having done. */
	public static final String READ_NOTICES = "READ_NOTICES";

	private final CallerIdentity caller;
	private final OperatorAccessLog accessLog;
	private final OperatorAccessLogReader accessLogReader;
	private final NoticeHistory notices;
	private final UserRepository users;

	public SupportController(CallerIdentity caller, OperatorAccessLog accessLog,
			OperatorAccessLogReader accessLogReader, NoticeHistory notices, UserRepository users) {
		this.caller = caller;
		this.accessLog = accessLog;
		this.accessLogReader = accessLogReader;
		this.notices = notices;
		this.users = users;
	}

	@GetMapping("/_notices")
	@Operation(summary = "What a customer was emailed, or why not",
			description = "Support only. Name the customer by email address, which is what they will "
					+ "give, or by id. Every email the platform meant to send them, newest first: sent, "
					+ "still being retried and why, or skipped and why. The read is recorded before it "
					+ "answers, as every staff read of a customer's data is (ADR 0025).")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The customer's emails, newest first"),
			@ApiResponse(responseCode = "400", description = "Neither or both of email and customerId"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify"),
			@ApiResponse(responseCode = "403", description = "OPERATOR_REQUIRED — not support"),
			@ApiResponse(responseCode = "404", description = "No account with that email")
	})
	public List<NoticeHistory.Entry> notices(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Parameter(description = "The customer's email address") @RequestParam(required = false) String email,
			@Parameter(description = "Or their id") @RequestParam(required = false) UUID customerId,
			@Parameter(description = "Only the emails about this order") @RequestParam(required = false) String orderNumber,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
		String staff = caller.require(authorization);
		// Checked here as well as by the filter, which is ADR 0021's rule for every staff read: the filter
		// is configuration, and configuration is what gets edited.
		if (!caller.maySeeAnotherCustomer(authorization)) {
			throw new OperatorRequiredException("Reading a customer's emails requires support");
		}
		if ((email == null) == (customerId == null)) {
			throw new BadRequestException("CUSTOMER_REQUIRED", "Name the customer by email or by customerId, not both");
		}
		String customer = customerId != null ? customerId.toString()
				: users.findByEmail(email.trim().toLowerCase())
						.orElseThrow(() -> ResourceNotFoundException.of("User", email))
						.getId().toString();

		// Before the read, not after: the record exists before anything is disclosed (ADR 0025).
		accessLog.record(staff, READ_NOTICES, orderNumber, customer);
		return notices.forCustomer(customer, orderNumber, limit);
	}

	@GetMapping("/_access-log")
	@Operation(summary = "Who read a customer's data in the user service",
			description = "Support only. Newest first, and the newest entry is this request. Covers this "
					+ "service only: the log lives in one database per service.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The recorded accesses, newest first"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify"),
			@ApiResponse(responseCode = "403", description = "OPERATOR_REQUIRED — not support")
	})
	public List<OperatorAccessLogReader.Entry> accessLog(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Parameter(description = "Whose data") @RequestParam @NotBlank String customerId,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return accessLogReader.forCustomer(authorization, customerId, limit);
	}

	@GetMapping("/_access-log/mine")
	@Operation(summary = "When staff looked at your account's data",
			description = "Signed in. Your own entries only, newest first: when a member of staff looked "
					+ "at what this service keeps about you, such as the emails sent to you -- not who. "
					+ "Covers this service only. See ADR 0056.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Staff accesses to your data, newest first"),
			@ApiResponse(responseCode = "401", description = "No token, or one that does not verify")
	})
	public List<OperatorAccessLogReader.CustomerEntry> myAccessLog(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return accessLogReader.mine(authorization, limit);
	}
}
