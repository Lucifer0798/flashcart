package com.flashcart.inventory.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.inventory.config.InventoryProperties;
import com.flashcart.inventory.config.InventoryProperties.ReservationStrategy;
import com.flashcart.inventory.domain.Reservation;
import com.flashcart.inventory.domain.ReservationLine;
import com.flashcart.inventory.domain.ReservationStatus;
import com.flashcart.inventory.domain.SaleAllocation;
import com.flashcart.inventory.domain.StockItem;
import com.flashcart.inventory.repository.CustomerSaleLimitRepository;
import com.flashcart.inventory.repository.ReservationRepository;
import com.flashcart.inventory.repository.SaleAllocationRepository;
import com.flashcart.inventory.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserve, commit and release — the whole reason this service exists.
 *
 * <h2>Why a reservation at all</h2>
 *
 * Between "buy now" and "payment confirmed" sits a person typing a card number and a payment
 * provider taking its time. Holding a database lock across that is how a flash sale takes the
 * database down. So stock is <em>held</em>: a durable, bounded claim that costs no lock while it
 * waits, and that resolves three ways — committed, released, or expired.
 *
 * <h2>Why this cannot oversell</h2>
 *
 * Every quantity check is a conditional {@code UPDATE} whose predicate and mutation are the same
 * statement (see {@link StockItemRepository#tryReserve}). There is no window between checking and
 * writing for a concurrent request to slip through, because there is no gap: the database
 * re-evaluates the condition against the committed row while holding it. A reserve that returns
 * zero rows means someone else won the unit, which during a sale is the ordinary outcome, not an
 * error condition.
 *
 * <h2>Three checks, not one</h2>
 *
 * A flash-sale reservation must satisfy all of: the warehouse has the units, the sale has not
 * consumed its allocation, and this customer is under their cap. Each is its own conditional update,
 * all inside one transaction, so any failure unwinds the others.
 */
@Service
public class ReservationService {

	private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

	private final ReservationRepository reservations;
	private final StockItemRepository stockItems;
	private final SaleAllocationRepository allocations;
	private final CustomerSaleLimitRepository customerLimits;
	private final ReservationExpiryService expiry;
	private final MovementRecorder movements;
	private final InventoryProperties properties;
	private final Clock clock;
	private final AvailabilityGate gate;
	private final TransactionTemplate transactions;

	public ReservationService(ReservationRepository reservations, StockItemRepository stockItems,
			SaleAllocationRepository allocations, CustomerSaleLimitRepository customerLimits,
			ReservationExpiryService expiry, MovementRecorder movements, InventoryProperties properties,
			Clock clock, AvailabilityGate gate, PlatformTransactionManager transactionManager) {
		this.reservations = reservations;
		this.stockItems = stockItems;
		this.allocations = allocations;
		this.customerLimits = customerLimits;
		this.expiry = expiry;
		this.movements = movements;
		this.properties = properties;
		this.clock = clock;
		this.gate = gate;
		this.transactions = new TransactionTemplate(transactionManager);
		// REQUIRES_NEW, because a refusal is an ordinary outcome that must not damage the caller's
		// transaction. Since Phase 8 the Kafka listener runs the claim and this call in one
		// transaction; joining it would mean a sold-out SKU marks that transaction rollback-only, the
		// listener's InventoryReservationFailed publish would be discarded with it, and the commit
		// would fail with UnexpectedRollbackException -- turning the single most ordinary flash-sale
		// outcome into a dead-lettered message and an order stuck in CREATED for ever.
		//
		// Its own transaction also keeps the all-lines-or-none guarantee: partial holds still roll
		// back, they just roll back alone.
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** One requested line, normalised. */
	public record RequestedLine(String sku, int quantity) {
	}

	/**
	 * Hold stock, all lines or none.
	 *
	 * <p><strong>Deliberately not {@code @Transactional}.</strong> The lazy reclaim below has to run
	 * in its own transaction so freed units stay freed even if this reservation then fails — but a
	 * {@code REQUIRES_NEW} call made from <em>inside</em> an open transaction needs a second pool
	 * connection while still holding the first. At sixty concurrent buyers against a twenty
	 * connection pool that deadlocks the pool outright: twenty requests each hold one connection and
	 * block forever waiting for a second that only they could release. Every buyer then gets a
	 * connection timeout instead of an answer.
	 *
	 * <p>So the reclaim happens here, before this method opens a transaction of its own, and the
	 * transactional work is opened explicitly afterwards. A {@link TransactionTemplate} rather than a
	 * second bean because the alternative — self-invoking an {@code @Transactional} method — silently
	 * bypasses the proxy and would not be transactional at all.
	 *
	 * <p>On the HTTP path nothing else is open, so that is one connection per request and the
	 * deadlock above cannot arise. Since Phase 8 the Kafka path does arrive with the idempotency
	 * claim's transaction already open, so a reserve there briefly holds two connections. That is
	 * bounded by listener concurrency — a handful of consumer threads, not sixty simultaneous
	 * buyers — which is why it is acceptable here and would not be on the request path.
	 *
	 * @param reservationKey the caller's idempotency key, normally the order id
	 * @param ttl            how long to hold for; falls back to the configured default, capped by
	 *                       the configured maximum
	 */
	public Reservation reserve(String reservationKey, String customerId, UUID flashSaleId,
			List<RequestedLine> requestedLines, Duration ttl) {

		List<RequestedLine> lines = normalise(requestedLines);

		for (RequestedLine line : lines) {
			// Units whose hold lapsed a moment ago belong back in the pool before we decide this
			// buyer is out of luck. Own transaction, own connection, nothing else held.
			expiry.reclaimExpiredForSku(line.sku());
		}

		try {
			return transactions.execute(status -> hold(reservationKey, customerId, flashSaleId, lines, ttl));
		}
		catch (DuplicateReservationKeyException ex) {
			// The losing side of a concurrent first-reserve. Re-read outside the rolled-back
			// transaction to hand back the hold the winner created.
			return reservations.findByReservationKey(reservationKey)
					.orElseThrow(() -> new ConflictException("RESERVATION_CONFLICT",
							"Reservation %s could not be created".formatted(reservationKey)));
		}
	}

	/**
	 * Signals that another transaction won the race to create this reservation key. Internal: it
	 * never escapes {@link #reserve}, which converts it into the winner's reservation.
	 */
	private static final class DuplicateReservationKeyException extends RuntimeException {

		DuplicateReservationKeyException(String reservationKey) {
			super("Reservation key " + reservationKey + " was created concurrently");
		}
	}

	/** The transactional body of {@link #reserve}: every check and write, all or nothing. */
	private Reservation hold(String reservationKey, String customerId, UUID flashSaleId,
			List<RequestedLine> lines, Duration ttl) {

		// Idempotency first, before anything is touched. A retried reserve — and retries are
		// certain, not hypothetical — must hand back the original hold, never take a second one.
		Reservation existing = reservations.findByReservationKey(reservationKey).orElse(null);
		if (existing != null) {
			log.debug("Reservation {} already exists with status {}; returning it unchanged",
					reservationKey, existing.getStatus());
			return existing;
		}

		Instant expiresAt = clock.instant().plus(resolveTtl(ttl));
		Reservation reservation = new Reservation(UUID.randomUUID(), reservationKey, customerId,
				flashSaleId, expiresAt);
		for (RequestedLine line : lines) {
			reservation.addLine(new ReservationLine(UUID.randomUUID(), line.sku(), line.quantity()));
		}

		for (RequestedLine line : lines) {
			if (flashSaleId != null) {
				chargeCustomerLimit(customerId, flashSaleId, line);
				claimSaleAllocation(flashSaleId, line);
			}
			holdStock(line);

			movements.reserved(line.sku(), line.quantity(), reservation.getId(), flashSaleId);
		}

		try {
			return reservations.saveAndFlush(reservation);
		}
		catch (DataIntegrityViolationException ex) {
			// Two concurrent calls with the same key both passed the lookup above. The unique
			// constraint is the real defence; the loser re-reads the winner's row.
			//
			// Re-read in a fresh transaction: this one is already marked rollback-only by the
			// constraint violation, so nothing further can be read through it.
			log.debug("Concurrent reserve for key {}; returning the winner's reservation", reservationKey);
			throw new DuplicateReservationKeyException(reservationKey);
		}
	}

	/**
	 * Turn a hold into a sale. Called when payment lands.
	 *
	 * <p>Idempotent: committing an already-committed reservation returns it unchanged, because the
	 * payment callback that triggers this arrives at least once and frequently more.
	 */
	@Transactional
	public Reservation commit(String reservationKey) {
		Reservation reservation = require(reservationKey);

		if (reservation.getStatus() == ReservationStatus.COMMITTED) {
			return reservation;
		}
		if (reservation.getStatus() != ReservationStatus.HELD) {
			// Committing an expired hold is the dangerous case: the units are already back in the
			// pool and may have been sold to someone else. Refusing here is what sends the order
			// service down its reconciliation path instead of confirming an order we cannot fill.
			throw new ConflictException("RESERVATION_NOT_HELD",
					"Reservation %s is %s and can no longer be committed"
							.formatted(reservationKey, reservation.getStatus()));
		}

		for (ReservationLine line : reservation.getLines()) {
			if (stockItems.commitReserved(line.getSku(), line.getQuantity()) == 0) {
				throw new ConflictException("RESERVATION_NOT_HELD",
						"Held units for %s are no longer available to commit".formatted(line.getSku()));
			}
			if (reservation.getFlashSaleId() != null) {
				allocations.commitReserved(reservation.getFlashSaleId(), line.getSku(), line.getQuantity());
				// Deliberately no customer-limit release: units the customer actually bought must
				// keep counting, or "one per customer" would only mean "one at a time".
			}
			movements.committed(line.getSku(), line.getQuantity(), reservation.getId(),
					reservation.getFlashSaleId());
		}

		reservation.setStatus(ReservationStatus.COMMITTED);
		reservation.setCommittedAt(clock.instant());
		return reservation;
	}

	/**
	 * Give a hold back before its timer runs out — an abandoned basket, a declined card, a cancelled
	 * order. Idempotent, and a no-op on a hold that already expired on its own.
	 */
	@Transactional
	public Reservation release(String reservationKey, String reason) {
		Reservation reservation = require(reservationKey);

		if (reservation.getStatus() != ReservationStatus.HELD) {
			// Already settled, one way or another. Releasing again would return the units twice.
			return reservation;
		}

		for (ReservationLine line : reservation.getLines()) {
			stockItems.releaseReserved(line.getSku(), line.getQuantity());
			// Back into circulation, so the gate stops refusing units that are available again.
			gate.release(line.getSku(), line.getQuantity());
			if (reservation.getFlashSaleId() != null) {
				allocations.releaseReserved(reservation.getFlashSaleId(), line.getSku(), line.getQuantity());
				customerLimits.release(reservation.getCustomerId(), reservation.getFlashSaleId(),
						line.getSku(), line.getQuantity());
			}
			movements.released(line.getSku(), line.getQuantity(), reservation.getId(),
					reservation.getFlashSaleId(), reason);
		}

		reservation.setStatus(ReservationStatus.RELEASED);
		reservation.setReleasedAt(clock.instant());
		return reservation;
	}

	@Transactional(readOnly = true)
	public Reservation get(String reservationKey) {
		return require(reservationKey);
	}

	@Transactional(readOnly = true)
	public List<Reservation> forCustomer(String customerId) {
		return reservations.findByCustomerIdOrderByCreatedAtDesc(customerId);
	}

	// --- the three checks -------------------------------------------------------------------------

	/**
	 * Hold the units, asking Redis first whether it is even worth asking PostgreSQL.
	 *
	 * <p>The gate can only refuse, never approve — see {@link AvailabilityGate}. So the structure
	 * below is not "Redis decides, database confirms"; it is "database decides, Redis skips the
	 * hopeless cases". During a sale that is most of them, and each one skipped is a connection and a
	 * row lock the buyers who <em>can</em> be served get to use instead.
	 */
	private void holdStock(RequestedLine line) {
		AvailabilityGate.Decision decision = gate.tryAdmit(line.sku(), line.quantity());
		if (decision == AvailabilityGate.Decision.REFUSED) {
			// Answered without touching the database at all. The gate may be wrong, and being wrong
			// this way costs a sale rather than oversells — the TTL repairs it within a minute.
			throw new InsufficientStockException(line.sku(), line.quantity());
		}

		boolean held = switch (properties.strategy()) {
			case ATOMIC_UPDATE -> stockItems.tryReserve(line.sku(), line.quantity()) == 1;
			case PESSIMISTIC_LOCK -> holdStockPessimistically(line);
		};

		if (!held) {
			if (decision == AvailabilityGate.Decision.ADMITTED) {
				// The gate let this through and the database said no, so its estimate was too
				// generous. Hand the token straight back: keeping it would make the counter
				// progressively more pessimistic and start refusing stock that genuinely exists.
				gate.release(line.sku(), line.quantity());
			}
			throw new InsufficientStockException(line.sku(), line.quantity());
		}

		if (decision == AvailabilityGate.Decision.UNKNOWN) {
			// The counter was cold or Redis was unreachable. Seed it from what the database now
			// knows, so the next buyer for this SKU gets the cheap answer.
			stockItems.findBySku(line.sku())
					.ifPresent(item -> gate.warm(item.getSku(), item.available()));
		}
	}

	/**
	 * The {@code SELECT ... FOR UPDATE} variant, selectable by configuration so Phase 10 can measure
	 * the two rather than argue about them.
	 *
	 * <p>Correct, and worse: the row lock is taken here and held until the transaction ends, so every
	 * other buyer of this SKU queues behind the rest of this request's work. The conditional update
	 * holds its lock for a single statement.
	 */
	private boolean holdStockPessimistically(RequestedLine line) {
		StockItem item = stockItems.findBySkuForUpdate(line.sku())
				.orElseThrow(() -> ResourceNotFoundException.of("Stock for SKU", line.sku()));
		if (item.available() < line.quantity()) {
			return false;
		}
		item.setReserved(item.getReserved() + line.quantity());
		return true;
	}

	private void claimSaleAllocation(UUID flashSaleId, RequestedLine line) {
		if (!allocations.existsByFlashSaleIdAndSku(flashSaleId, line.sku())) {
			// A sale that never allocated this SKU cannot sell it, even if the warehouse is full.
			throw new ConflictException("NO_SALE_ALLOCATION",
					"Flash sale %s has no allocation for %s".formatted(flashSaleId, line.sku()));
		}
		if (allocations.tryReserve(flashSaleId, line.sku(), line.quantity()) == 0) {
			throw new AllocationExhaustedException(flashSaleId, line.sku());
		}
	}

	private void chargeCustomerLimit(String customerId, UUID flashSaleId, RequestedLine line) {
		SaleAllocation allocation = allocations.findByFlashSaleIdAndSku(flashSaleId, line.sku())
				.orElseThrow(() -> new ConflictException("NO_SALE_ALLOCATION",
						"Flash sale %s has no allocation for %s".formatted(flashSaleId, line.sku())));
		int limit = allocation.getPerCustomerLimit();

		// Guards the INSERT branch of the upsert below, which its WHERE clause cannot reach. Racy?
		// No: both values are already in hand, so this is a pure comparison, not a read of shared state.
		if (line.quantity() > limit) {
			throw new CustomerLimitExceededException(line.sku(), limit);
		}
		if (customerLimits.tryConsume(UUID.randomUUID(), customerId, flashSaleId, line.sku(),
				line.quantity(), limit) == 0) {
			throw new CustomerLimitExceededException(line.sku(), limit);
		}
	}

	// --- helpers ---------------------------------------------------------------------------------

	private Reservation require(String reservationKey) {
		return reservations.findByReservationKey(reservationKey)
				.orElseThrow(() -> ResourceNotFoundException.of("Reservation", reservationKey));
	}

	/**
	 * Normalises and orders the requested lines (see {@link ReservationLines#normalise}, where the
	 * deadlock-avoiding sort lives), then checks every SKU is actually tracked.
	 */
	private List<RequestedLine> normalise(List<RequestedLine> requested) {
		List<RequestedLine> lines = ReservationLines.normalise(requested);
		for (RequestedLine line : lines) {
			if (!stockItems.existsBySku(line.sku())) {
				throw ResourceNotFoundException.of("Stock for SKU", line.sku());
			}
		}
		return lines;
	}

	private Duration resolveTtl(Duration requested) {
		if (requested == null) {
			return properties.reservationTtl();
		}
		if (requested.isNegative() || requested.isZero()) {
			throw new BadRequestException("Reservation TTL must be positive");
		}
		if (requested.compareTo(properties.maxReservationTtl()) > 0) {
			// Capped rather than rejected: a client asking for too long gets the longest we allow,
			// which is friendlier than a 400 and still stops anyone parking stock indefinitely.
			return properties.maxReservationTtl();
		}
		return requested;
	}
}
