package com.flashcart.inventory.api;

import java.util.List;
import java.util.UUID;

import com.flashcart.common.security.CallerIdentity;
import com.flashcart.inventory.api.dto.JoinWaitlistRequest;
import com.flashcart.inventory.api.dto.WaitlistEntryResponse;
import com.flashcart.inventory.service.WaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shopper's side of the waitlist: join, see where you stand, leave. See ADR 0044.
 *
 * <p>The only paths in this service a shopper may call. Every one acts as the token's subject and
 * nobody else -- there is no customer id to supply, so there is no way to join, read or leave somebody
 * else's queue by naming them.
 */
@RestController
@RequestMapping("/api/v1/inventory/waitlist")
@Tag(name = "Waitlist", description = "Queue for a sold-out SKU and be told when it is back")
public class WaitlistController {

	private final WaitlistService waitlist;
	private final CallerIdentity caller;

	public WaitlistController(WaitlistService waitlist, CallerIdentity caller) {
		this.waitlist = waitlist;
		this.caller = caller;
	}

	@PostMapping
	@Operation(summary = "Join the queue for a sold-out SKU",
			description = "Signed in. When units come back, the oldest waiters are told, as many as there "
					+ "are units. No unit is held for them. Joining again while waiting returns the same place.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Joined"),
			@ApiResponse(responseCode = "200", description = "Already waiting; the existing place"),
			@ApiResponse(responseCode = "401", description = "Not signed in"),
			@ApiResponse(responseCode = "404", description = "No such SKU"),
			@ApiResponse(responseCode = "409", description = "IN_STOCK -- there are units to buy now")
	})
	public ResponseEntity<WaitlistEntryResponse> join(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody JoinWaitlistRequest request) {
		WaitlistService.Place place = waitlist.join(caller.require(authorization), request.sku());
		return ResponseEntity.status(place.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(WaitlistEntryResponse.from(place));
	}

	@GetMapping("/mine")
	@Operation(summary = "Your queues, newest first, with how many are ahead of you in each")
	public List<WaitlistEntryResponse> mine(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return waitlist.mine(caller.require(authorization)).stream().map(WaitlistEntryResponse::from).toList();
	}

	@DeleteMapping("/{entryId}")
	@Operation(summary = "Leave a queue",
			description = "Leaving twice is harmless. Somebody else's entry is a 404, the same as one that "
					+ "does not exist.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Left, or had already left"),
			@ApiResponse(responseCode = "404", description = "No such entry of yours"),
			@ApiResponse(responseCode = "409", description = "ALREADY_NOTIFIED")
	})
	public WaitlistEntryResponse leave(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID entryId) {
		return WaitlistEntryResponse.from(
				new WaitlistService.Place(waitlist.leave(caller.require(authorization), entryId), null, false));
	}
}
