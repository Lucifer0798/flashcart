package com.flashcart.inventory.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.event.EventMetadata;
import com.flashcart.common.event.EventPublisher;
import com.flashcart.common.event.Topics;
import com.flashcart.common.event.message.BackInStock;
import com.flashcart.inventory.domain.StockItem;
import com.flashcart.inventory.domain.WaitlistEntry;
import com.flashcart.inventory.domain.WaitlistStatus;
import com.flashcart.inventory.repository.StockItemRepository;
import com.flashcart.inventory.repository.WaitlistRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue for sold-out SKUs. See ADR 0044.
 *
 * <h2>Who is told</h2>
 *
 * When {@code n} units of a SKU become available, the {@code n} oldest waiters are told, and nobody
 * else. Not everybody: telling five hundred people about three units rewards whoever checks out
 * fastest, which is the opposite of what queueing for something means. Each waiter is told at most
 * once, and a waiter who was told and missed the unit joins again at the back.
 *
 * <h2>What being told does not mean</h2>
 *
 * No unit is held for the shopper told. Another buyer who never joined the queue can still take it
 * first. Holding one would mean a reservation the order service did not create and would have to
 * adopt, which is a cross-service change worth making on its own and not inside this one.
 *
 * <h2>Where it is triggered from</h2>
 *
 * {@link MovementRecorder}, the one place every quantity change already goes through. Units come back
 * five ways -- received, adjusted up, a hold released, a hold expired, a sale returned -- and hooking
 * each would leave the sixth, whenever it is written, telling nobody. The ledger entry's own deltas say
 * how many units became available, and the claim runs in the transaction that freed them.
 */
@Service
public class WaitlistService {

	private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

	private final WaitlistRepository waitlist;
	private final StockItemRepository stockItems;
	private final EventPublisher events;
	private final Clock clock;
	private final TransactionTemplate fresh;
	private final Counter notified;

	public WaitlistService(WaitlistRepository waitlist, StockItemRepository stockItems, EventPublisher events,
			Clock clock, PlatformTransactionManager transactionManager, MeterRegistry meters) {
		this.waitlist = waitlist;
		this.stockItems = stockItems;
		this.events = events;
		this.clock = clock;
		this.fresh = new TransactionTemplate(transactionManager);
		this.notified = Counter.builder("flashcart.inventory.waitlist.notified")
				.description("Shoppers told a SKU they were waiting for is available again")
				.register(meters);
	}

	/** A place in a queue, and how many are ahead of it while it is still waiting. */
	public record Place(WaitlistEntry entry, Long ahead, boolean created) {
	}

	/**
	 * Join the queue for a sold-out SKU. Idempotent: joining again while waiting returns the same place.
	 *
	 * @throws ResourceNotFoundException when the SKU is not stocked at all
	 * @throws ConflictException         {@code IN_STOCK} when there are units to buy right now. Joining
	 *                                   would put the shopper behind a notice that already happened, so
	 *                                   they would wait for the next one instead of buying this one
	 */
	public Place join(String customerId, String sku) {
		String normalized = normalise(sku);
		StockItem item = stockItems.findBySku(normalized)
				.orElseThrow(() -> ResourceNotFoundException.of("Stock for SKU", normalized));
		if (item.available() > 0) {
			throw new ConflictException("IN_STOCK",
					"%s has %d available now; there is nothing to wait for".formatted(normalized, item.available()));
		}

		WaitlistEntry existing = waitlist.findBySkuAndCustomerIdAndStatus(normalized, customerId,
				WaitlistStatus.WAITING).orElse(null);
		if (existing != null) {
			return new Place(existing, waitlist.countAhead(normalized, existing.getCreatedAt(), existing.getId()),
					false);
		}

		try {
			WaitlistEntry entry = fresh.execute(status -> waitlist.saveAndFlush(
					new WaitlistEntry(UUID.randomUUID(), normalized, customerId, clock.instant())));
			return new Place(entry, waitlist.countAhead(normalized, entry.getCreatedAt(), entry.getId()), true);
		}
		catch (DataIntegrityViolationException ex) {
			// Two joins from the same shopper at once; the unique index let exactly one through.
			WaitlistEntry winner = waitlist.findBySkuAndCustomerIdAndStatus(normalized, customerId,
					WaitlistStatus.WAITING).orElseThrow(() -> ex);
			return new Place(winner, waitlist.countAhead(normalized, winner.getCreatedAt(), winner.getId()), false);
		}
	}

	/** Every queue this shopper has joined, newest first, with a position for the ones still waiting. */
	@Transactional(readOnly = true)
	public List<Place> mine(String customerId) {
		return waitlist.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
				.map(entry -> new Place(entry, entry.getStatus() == WaitlistStatus.WAITING
						? waitlist.countAhead(entry.getSku(), entry.getCreatedAt(), entry.getId())
						: null, false))
				.toList();
	}

	/**
	 * Leave a queue. Leaving twice is harmless; leaving after being told is refused, because the
	 * notice has already happened and cannot be taken back.
	 *
	 * @throws ResourceNotFoundException for an entry that does not exist <em>or belongs to somebody
	 *                                   else</em> -- the same answer for both, so entry ids cannot be
	 *                                   probed for other shoppers' queues
	 */
	@Transactional
	public WaitlistEntry leave(String customerId, UUID entryId) {
		WaitlistEntry entry = waitlist.findById(entryId)
				.filter(found -> found.getCustomerId().equals(customerId))
				.orElseThrow(() -> ResourceNotFoundException.of("Waitlist entry", entryId));
		switch (entry.getStatus()) {
			case WAITING -> entry.cancel(clock.instant());
			case CANCELLED -> { }
			case NOTIFIED -> throw new ConflictException("ALREADY_NOTIFIED",
					"This place in the queue was already told the SKU is back");
		}
		return entry;
	}

	/**
	 * {@code quantity} units of {@code sku} just became available: tell that many of the oldest waiters.
	 *
	 * <p>Joins the caller's transaction on purpose, and must have one. The notice, the stock change
	 * that caused it and the outbox row that announces it commit together or not at all -- a released
	 * hold that rolls back must not leave somebody told about units that never came back.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int unitsAvailable(String sku, int quantity) {
		if (quantity <= 0) {
			return 0;
		}
		Instant at = clock.instant();
		List<WaitlistEntry> told = waitlist.claimOldest(sku, quantity, at).stream()
				// RETURNING promises no order; the queue order is the one worth logging and publishing in.
				.sorted(Comparator.comparing(WaitlistEntry::getCreatedAt).thenComparing(WaitlistEntry::getId))
				.toList();
		for (WaitlistEntry entry : told) {
			events.publish(Topics.INVENTORY_EVENTS, new BackInStock(
					EventMetadata.of(BackInStock.TYPE, entry.getId()),
					entry.getId().toString(), entry.getSku(), entry.getCustomerId(), at));
		}
		if (!told.isEmpty()) {
			notified.increment(told.size());
			log.info("{} unit(s) of {} available again; told {} waiting shopper(s)", quantity, sku, told.size());
		}
		return told.size();
	}

	private static String normalise(String sku) {
		if (sku == null || sku.isBlank()) {
			throw new BadRequestException("SKU must not be blank");
		}
		return sku.trim().toUpperCase(Locale.ROOT);
	}
}
