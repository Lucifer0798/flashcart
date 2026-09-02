package com.flashcart.inventory.messaging;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.CommitInventory;
import com.flashcart.common.event.message.InventoryCommitted;
import com.flashcart.common.event.message.InventoryReleased;
import com.flashcart.common.event.message.InventoryReservationFailed;
import com.flashcart.common.event.message.InventoryReserved;
import com.flashcart.common.event.message.ReleaseInventory;
import com.flashcart.common.event.message.ReserveInventory;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.inventory.domain.Reservation;
import com.flashcart.inventory.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns commands from the order service into stock movements, and reports back what happened.
 *
 * <p>This is the replacement for Phase 4's synchronous HTTP call, and the shape of the change is the
 * point: inventory no longer answers a caller that is holding a connection open waiting. It is told
 * what to do, does it, and says what happened. A slow reservation no longer occupies a request
 * thread in the order service, and a burst of checkouts queues in Kafka — where queueing is free —
 * instead of in a connection pool, where it is not.
 *
 * <p><strong>Every handler is idempotent</strong>, because redelivery is certain rather than
 * unlikely: a consumer rebalance alone causes it. The underlying operations were already idempotent
 * on the reservation key from Phase 3, which is what makes these handlers safe to write this simply.
 *
 * <p>A handler that throws is retried with backoff and then dead-lettered. That matters most for
 * {@code reserve}: an expected refusal — sold out, cap reached — is <em>not</em> an exception here,
 * it is a published {@link InventoryReservationFailed}. Letting a sold-out SKU throw would send
 * perfectly ordinary business outcomes to the DLQ and stall the partition behind them.
 */
@Component
public class InventoryCommandListener {

	private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

	private final ReservationService reservations;
	private final EventPublisher events;

	public InventoryCommandListener(ReservationService reservations, EventPublisher events) {
		this.reservations = reservations;
		this.events = events;
	}

	@KafkaListener(topics = Topics.INVENTORY_COMMANDS, containerFactory = "reserveInventoryFactory",
			groupId = InventoryKafkaConfig.GROUP + "-reserve")
	public void onReserve(ReserveInventory command) {
		withCorrelationId(command.correlationId(), () -> {
			log.debug("Reserving stock for {}", command.reservationKey());
			try {
				List<ReservationService.RequestedLine> lines = command.lines().stream()
						.map(line -> new ReservationService.RequestedLine(line.sku(), line.quantity()))
						.toList();

				Reservation reservation = reservations.reserve(
						command.reservationKey(),
						command.customerId(),
						command.flashSaleId() == null ? null : UUID.fromString(command.flashSaleId()),
						lines,
						(Duration) null);

				events.publish(Topics.INVENTORY_EVENTS, new InventoryReserved(
						EventMetadata.of(InventoryReserved.TYPE, command.aggregateId()),
						reservation.getReservationKey(),
						reservation.getExpiresAt()));
			}
			catch (ConflictException | ResourceNotFoundException ex) {
				// An ordinary outcome, not a fault. Sold out, allocation gone, cap reached, unknown
				// SKU — all decisions the order service needs to hear about, none of them a reason
				// to retry or to dead-letter.
				log.info("Could not reserve for {}: {}", command.reservationKey(), ex.getMessage());
				events.publish(Topics.INVENTORY_EVENTS, new InventoryReservationFailed(
						EventMetadata.of(InventoryReservationFailed.TYPE, command.aggregateId()),
						command.reservationKey(),
						codeOf(ex),
						ex.getMessage()));
			}
		});
	}

	@KafkaListener(topics = Topics.INVENTORY_COMMANDS, containerFactory = "releaseInventoryFactory",
			groupId = InventoryKafkaConfig.GROUP + "-release")
	public void onRelease(ReleaseInventory command) {
		withCorrelationId(command.correlationId(), () -> {
			try {
				reservations.release(command.reservationKey(), command.reason());
			}
			catch (ResourceNotFoundException ex) {
				// Nothing to release. Reported as released anyway: the order service's compensation
				// must be able to complete, and "there was never a hold" satisfies "there is no hold
				// now" just as well as an actual release does.
				log.info("No reservation {} to release; reporting it released anyway",
						command.reservationKey());
			}
			events.publish(Topics.INVENTORY_EVENTS, new InventoryReleased(
					EventMetadata.of(InventoryReleased.TYPE, command.aggregateId()),
					command.reservationKey(), command.reason()));
		});
	}

	@KafkaListener(topics = Topics.INVENTORY_COMMANDS, containerFactory = "commitInventoryFactory",
			groupId = InventoryKafkaConfig.GROUP + "-commit")
	public void onCommit(CommitInventory command) {
		withCorrelationId(command.correlationId(), () -> {
			reservations.commit(command.reservationKey());
			events.publish(Topics.INVENTORY_EVENTS, new InventoryCommitted(
					EventMetadata.of(InventoryCommitted.TYPE, command.aggregateId()),
					command.reservationKey()));
		});
	}

	private static String codeOf(RuntimeException ex) {
		return ex instanceof com.flashcart.common.error.FlashCartException flashCart
				? flashCart.getCode() : "INVENTORY_REJECTED";
	}

	/**
	 * Puts the originating request's correlation id back into the MDC for the duration of the
	 * handler.
	 *
	 * <p>Without this a checkout stops being traceable the instant it crosses the bus: the listener
	 * runs on a Kafka consumer thread that never saw the HTTP request, so every log line it writes
	 * would be unattributable.
	 */
	private static void withCorrelationId(String correlationId, Runnable work) {
		if (correlationId != null) {
			MDC.put(CorrelationId.MDC_KEY, correlationId);
		}
		try {
			work.run();
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}
}
