package com.flashcart.common.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import com.flashcart.common.api.ApiError;
import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.FlashCartException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.order.IllegalOrderTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every uncaught failure as an {@link ApiError}, so no service leaks a stack trace or a
 * container's default HTML error page to a caller.
 *
 * <p>Registered by {@link CommonWebAutoConfiguration} rather than component-scanned, because each
 * service scans only its own package.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(FlashCartException.class)
	public ResponseEntity<ApiError> handleFlashCart(FlashCartException ex, HttpServletRequest request) {
		HttpStatus status = statusFor(ex);
		// 5xx is our bug and earns a stack trace; 4xx is the caller's and would just be log spam.
		if (status.is5xxServerError()) {
			log.error("{} handling {} {}", ex.getCode(), request.getMethod(), request.getRequestURI(), ex);
		}
		else {
			log.debug("{} handling {} {}: {}", ex.getCode(), request.getMethod(), request.getRequestURI(),
					ex.getMessage());
		}
		return build(status, ex.getCode(), ex.getMessage(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
				.toList();
		return ResponseEntity.badRequest()
				.body(ApiError.validation("Request validation failed", request.getRequestURI(),
						CorrelationId.current(), fields));
	}

	/** Fires for validation annotations on path variables and request params. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex,
			HttpServletRequest request) {
		List<ApiError.FieldError> fields = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new ApiError.FieldError(
								result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.toList();
		return ResponseEntity.badRequest()
				.body(ApiError.validation("Request validation failed", request.getRequestURI(),
						CorrelationId.current(), fields));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
			HttpServletRequest request) {
		List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
				.map(v -> new ApiError.FieldError(String.valueOf(v.getPropertyPath()), v.getMessage()))
				.toList();
		return ResponseEntity.badRequest()
				.body(ApiError.validation("Request validation failed", request.getRequestURI(),
						CorrelationId.current(), fields));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request could not be read", request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint for this path", request);
	}

	/**
	 * The right path, the wrong verb.
	 *
	 * <p>Without this, Spring's {@code HttpRequestMethodNotSupportedException} falls through to the
	 * catch-all below and every mistyped verb becomes a 500 — telling the caller the server is broken
	 * when in fact their request was. It is a client error, and it says so.
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiError> handleWrongMethod(HttpRequestMethodNotSupportedException ex,
			HttpServletRequest request) {
		return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
				"%s is not supported for this path".formatted(ex.getMethod()), request);
	}

	/** The right path and verb, a body this endpoint cannot read. Also a 4xx, for the same reason. */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiError> handleWrongMediaType(HttpMediaTypeNotSupportedException ex,
			HttpServletRequest request) {
		return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
				"Content type is not supported for this path", request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
		// Deliberately generic: the detail is in the log, keyed by the correlation id we return.
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
	}

	private static HttpStatus statusFor(FlashCartException ex) {
		if (ex instanceof ResourceNotFoundException) {
			return HttpStatus.NOT_FOUND;
		}
		if (ex instanceof ConflictException || ex instanceof IllegalOrderTransitionException) {
			return HttpStatus.CONFLICT;
		}
		if (ex instanceof BadRequestException) {
			return HttpStatus.BAD_REQUEST;
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
				.body(ApiError.of(status.value(), code, message, request.getRequestURI(), CorrelationId.current()));
	}
}
