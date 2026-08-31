package com.flashcart.order.client;

import java.time.Instant;
import java.util.Map;

import com.flashcart.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The synchronous HTTP implementation, and a temporary one — Phase 5 replaces it with events.
 *
 * <p>Two things it takes seriously.
 *
 * <p><strong>Telling refusal apart from silence.</strong> A 4xx from inventory is a decision: no
 * stock, allocation gone, cap reached. Anything else — a timeout, a connection reset, a 5xx — means
 * the hold's existence is unknown, and the two must never be collapsed into one failure, because one
 * is safe to cancel on and the other is not.
 *
 * <p><strong>Propagating the correlation id.</strong> The gateway stamps it, this service carries it
 * over the hop, and inventory logs it too — otherwise a checkout stops being traceable the moment it
 * leaves this process.
 */
public class RestInventoryClient implements InventoryClient {

	private static final Logger log = LoggerFactory.getLogger(RestInventoryClient.class);

	private final RestClient http;

	public RestInventoryClient(RestClient http) {
		this.http = http;
	}

	@Override
	public Reservation reserve(ReserveCommand command) {
		try {
			ReservationResponse response = http.post()
					.uri("/api/v1/inventory/reservations")
					.headers(headers -> addCorrelationId(headers::set))
					.body(command)
					.exchange((request, clientResponse) -> {
						HttpStatusCode status = clientResponse.getStatusCode();
						if (status.is2xxSuccessful()) {
							return clientResponse.bodyTo(ReservationResponse.class);
						}
						if (status.is4xxClientError()) {
							// A decision, not a fault. Inventory's own code travels through unchanged.
							throw rejected(clientResponse.bodyTo(Map.class), status);
						}
						throw new InventoryUnavailableException(
								"Inventory answered %s while reserving".formatted(status), null);
					});

			return new Reservation(response.reservationKey(), response.status(), response.expiresAt());
		}
		catch (ResourceAccessException ex) {
			// A timeout or a connection failure. The hold may exist; we cannot tell from here.
			log.warn("Inventory unreachable while reserving {}: {}", command.reservationKey(),
					ex.getMessage());
			throw new InventoryUnavailableException("Inventory could not be reached while reserving", ex);
		}
	}

	@Override
	public void commit(String reservationKey) {
		post("/api/v1/inventory/reservations/" + reservationKey + "/commit", null, "committing");
	}

	@Override
	public void release(String reservationKey, String reason) {
		post("/api/v1/inventory/reservations/" + reservationKey + "/release",
				Map.of("reason", reason == null ? "released by the order service" : reason), "releasing");
	}

	private void post(String uri, Object body, String what) {
		try {
			RestClient.RequestBodySpec spec = http.post()
					.uri(uri)
					.headers(headers -> addCorrelationId(headers::set));
			RestClient.RequestHeadersSpec<?> request = body == null ? spec : spec.body(body);

			request.exchange((req, response) -> {
				HttpStatusCode status = response.getStatusCode();
				if (status.is2xxSuccessful()) {
					return null;
				}
				if (status.is4xxClientError()) {
					throw rejected(response.bodyTo(Map.class), status);
				}
				throw new InventoryUnavailableException(
						"Inventory answered %s while %s".formatted(status, what), null);
			});
		}
		catch (ResourceAccessException ex) {
			throw new InventoryUnavailableException(
					"Inventory could not be reached while %s".formatted(what), ex);
		}
	}

	private static InventoryRejectedException rejected(Map<?, ?> body, HttpStatusCode status) {
		String code = body == null ? null : String.valueOf(body.get("code"));
		String message = body == null ? "Inventory refused the request (%s)".formatted(status)
				: String.valueOf(body.get("message"));
		return new InventoryRejectedException(code, message);
	}

	private static void addCorrelationId(java.util.function.BiConsumer<String, String> setter) {
		String correlationId = CorrelationId.current();
		if (correlationId != null) {
			setter.accept(CorrelationId.HEADER, correlationId);
		}
	}

	/** Inventory's reservation shape, narrowed to the three fields an order actually needs. */
	private record ReservationResponse(String reservationKey, String status, Instant expiresAt) {
	}
}
