package com.flashcart.order.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.flashcart.common.error.BadRequestException;
import com.flashcart.common.error.ConflictException;
import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.common.order.OrderStateMachine;
import com.flashcart.common.order.OrderStatus;
import com.flashcart.common.web.CorrelationId;
import com.flashcart.order.client.CatalogClient;
import com.flashcart.order.client.InventoryClient;
import com.flashcart.order.client.InventoryRejectedException;
import com.flashcart.order.client.InventoryUnavailableException;
import com.flashcart.order.domain.Order;
import com.flashcart.order.domain.OrderLine;
import com.flashcart.order.domain.OrderStatusChange;
import com.flashcart.order.repository.OrderRepository;
import com.flashcart.order.repository.OrderStatusChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places orders and drives them through the state machine.
 *
 * <h2>The shape of a checkout</h2>
 *
 * <pre>
 * price the basket from catalog   →  CREATED
 * hold the stock in inventory     →  RESERVED
 * ask for payment                 →  PAYMENT_PENDING   (payment itself is Phase 6)
 * </pre>
 *
 * and every way that can fail leads somewhere defined, never nowhere.
 *
 * <h2>Why no transaction spans the network calls</h2>
 *
 * The obvious implementation wraps the whole checkout in one {@code @Transactional} method. That
 * holds a database connection open across two HTTP calls to other services, so a slow inventory
 * drains this service's connection pool and takes it down with it. Worse, it is a lie: the remote
 * hold is not rolled back by a local rollback, so a failure after reserving leaves stock held by an
 * order that no longer exists.
 *
 * <p>So the transactions are small and explicit, the network calls sit between them, and the
 * compensation is written out rather than hoped for.
 */
@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	/** Unambiguous in print and on the phone: no I, O, 0 or 1. */
	private static final char[] ORDER_NUMBER_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final OrderRepository orders;
	private final OrderStatusChangeRepository history;
	private final CatalogClient catalog;
	private final InventoryClient inventory;
	private final TransactionTemplate transactions;

	public OrderService(OrderRepository orders, OrderStatusChangeRepository history, CatalogClient catalog,
			InventoryClient inventory, PlatformTransactionManager transactionManager) {
		this.orders = orders;
		this.history = history;
		this.catalog = catalog;
		this.inventory = inventory;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** One requested line, before catalog has priced it. */
	public record RequestedLine(String sku, int quantity) {
	}

	/**
	 * Place an order: price it, persist it, and hold the stock.
	 *
	 * <p>Deliberately not {@code @Transactional} — see the class comment.
	 *
	 * @param idempotencyKey the caller's key. A double-tapped button or a client retry returns the
	 *                       original order rather than placing a second one.
	 */
	public Order place(String idempotencyKey, String customerId, UUID flashSaleId,
			List<RequestedLine> requestedLines) {

		Order existing = orders.findByIdempotencyKey(idempotencyKey).orElse(null);
		if (existing != null) {
			if (existing.getStatus() == OrderStatus.CREATED) {
				// Stranded by an earlier attempt where inventory never answered. Returning it as-is
				// would make the retry useless — the order would sit in CREATED forever, which is
				// precisely the state the "leave it retryable" decision exists to escape. So resume
				// where it stopped, which is safe because the reservation key makes inventory's
				// reserve idempotent: if that earlier call did land, this one returns the same hold.
				log.info("Resuming order {} that was left CREATED by an earlier attempt",
						existing.getOrderNumber());
				return reserveStockFor(existing);
			}
			log.debug("Order for idempotency key {} already exists as {}", idempotencyKey,
					existing.getOrderNumber());
			return existing;
		}

		List<RequestedLine> lines = normalise(requestedLines);
		// Priced before anything is persisted: if catalog cannot answer, nothing has happened yet
		// and there is no order to clean up.
		List<PricedLine> priced = price(lines);

		Order order;
		try {
			order = transactions.execute(status -> create(idempotencyKey, customerId, flashSaleId, priced));
		}
		catch (DuplicateIdempotencyKeyException ex) {
			// The losing side of two concurrent checkouts with one key. Re-read outside the
			// rolled-back transaction and hand back the order the winner placed.
			return orders.findByIdempotencyKey(idempotencyKey)
					.orElseThrow(() -> new ConflictException("ORDER_CONFLICT",
							"Order for key %s could not be placed".formatted(idempotencyKey)));
		}

		return reserveStockFor(order);
	}

	/**
	 * Ask inventory to hold the stock, then move {@code CREATED → RESERVED}.
	 *
	 * <p>The three outcomes are genuinely different and each is handled on its own terms.
	 */
	private Order reserveStockFor(Order order) {
		InventoryClient.ReserveCommand command = new InventoryClient.ReserveCommand(
				order.getReservationKey(),
				order.getCustomerId(),
				order.getFlashSaleId(),
				order.getLines().stream()
						.map(line -> new InventoryClient.ReserveCommand.Line(line.getSku(), line.getQuantity()))
						.toList());

		InventoryClient.Reservation reservation;
		try {
			reservation = inventory.reserve(command);
		}
		catch (InventoryRejectedException ex) {
			// A decision, and a final one. The order is cancelled with inventory's reason attached,
			// and kept rather than deleted: "why could I not buy this" is a real support question.
			log.info("Inventory refused order {}: {}", order.getOrderNumber(), ex.getCode());
			transactions.executeWithoutResult(status ->
					transition(order.getId(), OrderStatus.CANCELLED, ex.getMessage()));
			throw ex;
		}
		catch (InventoryUnavailableException ex) {
			// Silence, not refusal. The hold may exist. Cancelling could strand real stock until it
			// expires, and confirming could promise stock nobody is holding — so the order stays
			// CREATED and the caller retries with the same idempotency key, which is safe precisely
			// because the reservation key makes inventory idempotent too.
			log.warn("Inventory unavailable for order {}; leaving it CREATED for retry",
					order.getOrderNumber());
			throw ex;
		}

		return transactions.execute(status -> {
			Order reserved = require(order.getId());
			reserved.setReservationExpiresAt(reservation.expiresAt());
			history.save(reserved.transitionTo(OrderStatus.RESERVED, "stock held by inventory",
					CorrelationId.current()));
			return reserved;
		});
	}

	/**
	 * Move a held order to {@code PAYMENT_PENDING}.
	 *
	 * <p>Phase 6 replaces the body with a real payment request; the transition and its guards are
	 * what matter now, and they do not change when it does.
	 */
	@Transactional
	public Order requestPayment(String orderNumber) {
		Order order = requireByNumber(orderNumber);
		if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
			// Idempotent: asking twice is a retry, not an error.
			return order;
		}
		history.save(order.transitionTo(OrderStatus.PAYMENT_PENDING, "payment requested",
				CorrelationId.current()));
		return order;
	}

	/**
	 * The customer or an operator cancels.
	 *
	 * <p>Releases the hold first, then records the cancellation. That order matters: if the release
	 * fails, the order stays as it was and can be retried, whereas cancelling first would leave an
	 * order that looks finished while inventory is still holding its stock.
	 */
	public Order cancel(String orderNumber, String reason) {
		Order order = transactions.execute(status -> requireByNumber(orderNumber));

		if (order.getStatus() == OrderStatus.CANCELLED) {
			return order;
		}
		// Checked *before* releasing anything. The release below is not undoable by a rollback, so
		// discovering afterwards that the transition was illegal would leave the stock given back and
		// the order still live — the worst of both. In particular there is deliberately no
		// PAYMENT_PENDING -> CANCELLED edge: a charge is in flight, and it has to be resolved rather
		// than walked away from.
		if (!OrderStateMachine.canTransition(order.getStatus(), OrderStatus.CANCELLED)) {
			throw new ConflictException("ORDER_NOT_CANCELLABLE",
					order.getStatus() == OrderStatus.PAYMENT_PENDING
							? ("Order %s has a payment in flight; resolve the payment before cancelling"
									.formatted(orderNumber))
							: ("Order %s is %s and can no longer be cancelled"
									.formatted(orderNumber, order.getStatus())));
		}

		if (order.holdsInventory()) {
			inventory.release(order.getReservationKey(),
					reason == null ? "order cancelled" : reason);
		}

		return transactions.execute(status ->
				transition(order.getId(), OrderStatus.CANCELLED, reason == null ? "cancelled" : reason));
	}

	/**
	 * Payment came back declined.
	 *
	 * <p>Phase 6 drives this from a real provider callback. The path is
	 * {@code PAYMENT_PENDING → PAYMENT_FAILED → CANCELLED}, with the stock released in between —
	 * compensation as a persisted state rather than a side effect, so an order is never quietly
	 * cancelled while inventory still holds its units.
	 */
	public Order paymentFailed(String orderNumber, String reason) {
		Order order = transactions.execute(status -> {
			Order found = requireByNumber(orderNumber);
			history.save(found.transitionTo(OrderStatus.PAYMENT_FAILED,
					reason == null ? "payment declined" : reason, CorrelationId.current()));
			return found;
		});

		inventory.release(order.getReservationKey(), "payment declined");

		return transactions.execute(status ->
				transition(order.getId(), OrderStatus.CANCELLED, "payment declined"));
	}

	@Transactional(readOnly = true)
	public Order get(String orderNumber) {
		return requireByNumber(orderNumber);
	}

	@Transactional(readOnly = true)
	public List<Order> forCustomer(String customerId) {
		return orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
	}

	@Transactional(readOnly = true)
	public List<OrderStatusChange> historyOf(String orderNumber) {
		return history.findByOrderIdOrderByCreatedAtAsc(requireByNumber(orderNumber).getId());
	}

	// --- internals -------------------------------------------------------------------------------

	private Order create(String idempotencyKey, String customerId, UUID flashSaleId, List<PricedLine> priced) {
		String currency = priced.get(0).currency();
		if (priced.stream().anyMatch(line -> !line.currency().equals(currency))) {
			// Summing across currencies would produce a total that means nothing. Refused rather
			// than converted: an exchange rate is a decision this service has no business making.
			throw new BadRequestException("MIXED_CURRENCY",
					"An order cannot mix currencies; this basket has more than one");
		}

		Order order = new Order(UUID.randomUUID(), nextOrderNumber(), customerId, flashSaleId, currency,
				idempotencyKey);
		for (PricedLine line : priced) {
			order.addLine(new OrderLine(UUID.randomUUID(), line.sku(), line.name(), line.quantity(),
					line.unitPrice()));
		}

		try {
			orders.saveAndFlush(order);
		}
		catch (DataIntegrityViolationException ex) {
			// Two concurrent checkouts with the same idempotency key both passed the lookup. The
			// unique constraint is the real defence; the loser reads the winner's order.
			throw new DuplicateIdempotencyKeyException(idempotencyKey);
		}
		history.save(order.creationRecord(CorrelationId.current()));
		return order;
	}

	/** Applies a transition to a freshly loaded order, so the version check is against current state. */
	private Order transition(UUID orderId, OrderStatus next, String reason) {
		Order order = require(orderId);
		history.save(order.transitionTo(next, reason, CorrelationId.current()));
		return order;
	}

	private List<PricedLine> price(List<RequestedLine> lines) {
		List<PricedLine> priced = new ArrayList<>(lines.size());
		for (RequestedLine line : lines) {
			CatalogClient.PricedProduct product = catalog.priceOf(line.sku());
			// The effective price, which already accounts for any live flash sale — and taken from
			// catalog, never from the request, because a checkout that trusts a client-supplied
			// price is a checkout anyone can discount to zero.
			priced.add(new PricedLine(product.sku(), product.name(), line.quantity(),
					product.effectivePrice(), product.currency()));
		}
		return priced;
	}

	private List<RequestedLine> normalise(List<RequestedLine> requested) {
		if (requested == null || requested.isEmpty()) {
			throw new BadRequestException("An order must have at least one line");
		}
		Set<String> seen = new LinkedHashSet<>();
		List<RequestedLine> lines = new ArrayList<>(requested.size());
		for (RequestedLine line : requested) {
			String sku = line.sku().trim().toUpperCase(Locale.ROOT);
			if (line.quantity() <= 0) {
				throw new BadRequestException("Quantity for %s must be positive".formatted(sku));
			}
			if (!seen.add(sku)) {
				throw new BadRequestException(
						"SKU %s appears more than once; combine the quantities".formatted(sku));
			}
			lines.add(new RequestedLine(sku, line.quantity()));
		}
		return lines;
	}

	private Order require(UUID id) {
		return orders.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Order", id));
	}

	private Order requireByNumber(String orderNumber) {
		return orders.findByOrderNumber(orderNumber.toUpperCase(Locale.ROOT))
				.orElseThrow(() -> ResourceNotFoundException.of("Order", orderNumber));
	}

	/**
	 * A short, unambiguous reference customers can read out loud.
	 *
	 * <p>Random rather than sequential on purpose: a sequential order number tells any customer how
	 * many orders the business has taken, and lets them guess their neighbour's.
	 */
	private String nextOrderNumber() {
		for (int attempt = 0; attempt < 5; attempt++) {
			StringBuilder builder = new StringBuilder("FC-");
			for (int i = 0; i < 8; i++) {
				builder.append(ORDER_NUMBER_ALPHABET[RANDOM.nextInt(ORDER_NUMBER_ALPHABET.length)]);
			}
			String candidate = builder.toString();
			if (!orders.existsByOrderNumber(candidate)) {
				return candidate;
			}
		}
		// 32^8 possibilities; five collisions in a row means something is very wrong, and silently
		// carrying on with a duplicate would be worse than failing loudly.
		throw new IllegalStateException("Could not allocate a unique order number");
	}

	/** Internal signal that another transaction won the race on an idempotency key. */
	static final class DuplicateIdempotencyKeyException extends RuntimeException {

		DuplicateIdempotencyKeyException(String key) {
			super("Idempotency key " + key + " was used concurrently");
		}
	}

	private record PricedLine(String sku, String name, int quantity, BigDecimal unitPrice, String currency) {
	}
}
