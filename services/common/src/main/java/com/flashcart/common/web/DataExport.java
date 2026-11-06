package com.flashcart.common.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * A customer's own data from one service, as a file to keep. See ADR 0066.
 *
 * <p>One per service, because each service holds its own and the platform does not fan out between them
 * (ADR 0034, ADR 0056). All five answer in this one shape, so a client gathering them handles one thing, and
 * a file says by itself where it came from, whose it is and when it was taken.
 */
public final class DataExport {

	/**
	 * How many staff reads to ask the access log for. It takes a limit, and a file that quietly stopped short
	 * would claim to be everything while not being it, so this is far past what its retention (ADR 0026) can
	 * hold for one customer, rather than a page size.
	 */
	public static final int ALL_STAFF_READS = 100_000;

	private DataExport() {
	}

	/** @param sections what this service holds, by name, in the order they should read */
	public static ResponseEntity<Map<String, Object>> of(String service, String customerId,
			Map<String, Object> sections) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("service", service);
		body.put("customerId", customerId);
		body.put("exportedAt", Instant.now());
		body.putAll(sections);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"flashcart-" + service + "-export.json\"")
				.body(body);
	}
}
