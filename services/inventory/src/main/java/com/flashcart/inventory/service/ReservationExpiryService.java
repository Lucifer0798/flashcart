package com.flashcart.inventory.service;

import java.util.List;
import java.util.UUID;

import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.ReservationExpired;
import com.flashcart.inventory.config.InventoryProperties;
import com.flashcart.inventory.domain.Reservation;
import com.flashcart.inventory.domain.ReservationLine;
import com.flashcart.inventory.domain.ReservationStatus;
import com.flashcart.inventory.repository.CustomerSaleLimitRepository;
import com.flashcart.inventory.repository.ReservationRepository;
import com.flashcart.inventory.repository.SaleAllocationRepository;
import com.flashcart.inventory.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns units held by reservations whose time ran out.
 *
 * <p>Expiry runs on two paths, and the split is deliberate:
 *
 * <ul>
 *   <li><strong>Lazily</strong>, from {@link ReservationService} just before it tries to reserve a
 *       SKU. This is the one that matters for correctness of experience: a buyer must never be told
 *       "sold out" because a background job had not got round to reclaiming units that expired
 *       thirty seconds ago. At the top of a sale, when the sweeper is most likely to be behind, this
 *       is what keeps the number honest.</li>
 *   <li><strong>On a schedule</strong>, for everything the lazy path never touches — a SKU nobody is
 *       asking about any more, or a sale that has ended. Without it, a cold SKU's expired holds
 *       would sit there forever. It is also where Phase 5 will publish {@code ReservationExpired},
 *       which is what lets the order service move its own state machine.</li>
 * </ul>
 *
 * <p>Every expiry runs in its <em>own</em> transaction ({@link Propagation#REQUIRES_NEW}). The lazy
 * path is called from inside a reservation attempt that may well fail and roll back, and units that
 * genuinely expired must stay freed regardless of what happens to the request that noticed.
 */
@Service
public class ReservationExpiryService {

	private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);

	private final ReservationRepository reservations;
	private final StockItemRepository stockItems;
	private final SaleAllocationRepository allocations;
	private final CustomerSaleLimitRepository customerLimits;
	private final MovementRecorder movements;
	private final InventoryProperties properties;
	private final EventPublisher events;

	public ReservationExpiryService(ReservationRepository reservations, StockItemRepository stockItems,
			SaleAllocationRepository allocations, CustomerSaleLimitRepository customerLimits,
			MovementRecorder movements, InventoryProperties properties, EventPublisher events) {
		this.reservations = reservations;
		this.stockItems = stockItems;
		this.allocations = allocations;
		this.customerLimits = customerLimits;
		this.movements = movements;
		this.properties = properties;
		this.events = events;
	}

	/**
	 * Reclaim expired holds touching {@code sku}, inline on the reserve path.
	 *
	 * @return how many reservations were expired
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int reclaimExpiredForSku(String sku) {
		List<UUID> expiredIds = reservations.claimExpiredForSku(sku, properties.lazyReclaimLimit());
		return expireAll(expiredIds, "lazy reclaim");
	}

	/**
	 * The background sweep.
	 *
	 * <p>Safe to run on every instance at once: {@code claimExpired} uses {@code SKIP LOCKED}, and
	 * the status transition below only fires on rows still {@code HELD}, so two sweepers racing on
	 * the same reservation cannot both release its units.
	 */
	@Scheduled(
			fixedDelayString = "${flashcart.inventory.sweeper.fixed-delay:PT10S}",
			initialDelayString = "${flashcart.inventory.sweeper.initial-delay:PT15S}")
	public void sweep() {
		if (!properties.sweeper().enabled()) {
			return;
		}
		try {
			int expired = sweepBatch();
			if (expired > 0) {
				log.info("Sweeper expired {} reservation(s) and returned their units to stock", expired);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is silently not rescheduled by some executors, and a
			// sweeper that quietly stops is a slow stock leak. Swallow, log, and try again next tick.
			log.error("Reservation sweep failed; will retry on the next tick", ex);
		}
	}

	/** The sweep body, separated so tests can drive one batch deterministically. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int sweepBatch() {
		List<UUID> expiredIds = reservations.claimExpired(properties.sweeper().batchSize());
		return expireAll(expiredIds, "sweeper");
	}

	private int expireAll(List<UUID> reservationIds, String source) {
		int expired = 0;
		for (UUID id : reservationIds) {
			if (expire(id, source)) {
				expired++;
			}
		}
		return expired;
	}

	private boolean expire(UUID reservationId, String source) {
		Reservation reservation = reservations.findById(reservationId).orElse(null);
		// Re-checked rather than trusted: between the claim query and here, a commit or an explicit
		// release may have landed. Returning units for a reservation that already settled would
		// hand back stock twice.
		if (reservation == null || reservation.getStatus() != ReservationStatus.HELD) {
			return false;
		}

		for (ReservationLine line : reservation.getLines()) {
			stockItems.releaseReserved(line.getSku(), line.getQuantity());
			if (reservation.getFlashSaleId() != null) {
				allocations.releaseReserved(reservation.getFlashSaleId(), line.getSku(), line.getQuantity());
				// The customer's allowance comes back: their hold lapsed, they did not buy.
				customerLimits.release(reservation.getCustomerId(), reservation.getFlashSaleId(),
						line.getSku(), line.getQuantity());
			}
			movements.expired(line.getSku(), line.getQuantity(), reservation.getId(),
					reservation.getFlashSaleId());
		}

		reservation.setStatus(ReservationStatus.EXPIRED);
		reservation.setReleasedAt(java.time.Instant.now());

		// Tell the order service rather than leaving it to notice. Its own reconciler mirrors the
		// expiry time and would eventually catch up, but that is the backstop; this is the signal.
		events.publish(Topics.INVENTORY_EVENTS, new ReservationExpired(
				EventMetadata.of(ReservationExpired.TYPE, reservation.getReservationKey()),
				reservation.getReservationKey()));

		log.debug("Expired reservation {} via {}", reservation.getReservationKey(), source);
		return true;
	}
}
