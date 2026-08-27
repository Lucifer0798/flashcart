package com.flashcart.common.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one error body every FlashCart service returns, so a client can parse failures the same way
 * whether they came from the gateway or from a service four hops downstream.
 *
 * @param timestamp     when the failure was rendered
 * @param status        HTTP status code, repeated in the body for clients that lose the envelope
 * @param code          stable machine-readable code, e.g. {@code SOLD_OUT}
 * @param message       human-readable summary; never contains internal detail
 * @param path          the request path that failed
 * @param correlationId ties this response to the log/trace of the whole request chain
 * @param fieldErrors   per-field detail for validation failures, otherwise omitted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
		Instant timestamp,
		int status,
		String code,
		String message,
		String path,
		String correlationId,
		List<FieldError> fieldErrors) {

	/** One rejected field of a request body. */
	public record FieldError(String field, String message) {
	}

	public static ApiError of(int status, String code, String message, String path, String correlationId) {
		return new ApiError(Instant.now(), status, code, message, path, correlationId, null);
	}

	public static ApiError validation(String message, String path, String correlationId, List<FieldError> fieldErrors) {
		return new ApiError(Instant.now(), 400, "VALIDATION_FAILED", message, path, correlationId, fieldErrors);
	}
}
