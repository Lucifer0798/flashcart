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
 * Places orders. Everything that happens after placement is {@link OrderSaga}'s.
 *
 * <h2>What changed in Phase 5</h2>
 *
 * Placing an order no longer waits for inventory. It prices the basket, persists the order as
 * {@code CREATED}, publishes {@code ReserveInventory}, and returns — so a checkout responds in the
 * time it takes to write one row, and the reservation settles asynchronously.
 *
 * <p>That is the right shape for a flash sale specifically. A synchronous reserve means ten thousand
 * simultaneous checkouts become ten thousand simultaneous open connections waiting on the one
 * contended service in the platform; the queue forms in a connection pool, where it is expensive and
 * where it eventually fails. Publishing instead moves the queue into Kafka, where a backlog is
 * ordinary and where nothing is holding a thread while it waits.
 *
 * <p>The cost is honest and worth stating: the customer no longer learns instantly whether they got
 * the item. The API returns {@code 202 Accepted} with a {@code CREATED} order, and the client watches
 * it become {@code RESERVED} or {@code CANCELLED}.
 *
 * <h2>What did not change</h2>
 *
 * Pricing is still a synchronous call to catalog. It is a query, not a command — the order cannot be
 * written without it, there is nothing to compensate if it fails, and making it asynchronous would
 * add a state to the machine to express "an order that does not yet know what it costs".
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
	private final OrderSaga saga;
	private final TransactionTemplate transactions;

	public OrderService(OrderRepository orders, OrderStatusChangeRepository history, CatalogClient catalog,
			OrderSaga saga, PlatformTransactionManager transactionManager) {
		this.orders = orders;
		this.history = history;
		this.catalog = catalog;
		this.saga = saga;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** One requested line, before catalog has priced it. */
	public record RequestedLine(String sku, int quantity) {
	}

	/**
	 * Place an order: price it, persist it, and ask inventory to hold the stock.
	 *
	 * <p>Returns as soon as the order exists and the command is on its way. The order comes back
	 * {@code CREATED}; the saga moves it from there.
	 *
	 * @param idempotencyKey the caller's key. A double-tapped button or a client retry returns the
	 *                       original order rather than placing a second one.
	 */
	public Order place(String idempotencyKey, String customerId, UUID flashSaleId,
			List<RequestedLine> requestedLines) {

		Order existing = orders.findByIdempotencyKey(idempotencyKey).orElse(null);
		if (existing != null) {
			if (existing.getStatus() == OrderStatus.CREATED) {
				// Still waiting on inventory. The command may have been lost before it reached the
				// broker, so re-send it: ReserveInventory is idempotent on the reservation key, so a
				// duplicate either creates the hold or returns the one the first attempt made.
				log.info("Re-sending the reservation command for order {}, still CREATED",
						existing.getOrderNumber());
				saga.requestReservation(existing);
				return existing;
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
			return orders.findByIdempotencyKey(idempotencyKey)
					.orElseThrow(() -> new ConflictException("ORDER_CONFLICT",
							"Order for key %s could not be placed".formatted(idempotencyKey)));
		}

		// Published after the transaction has committed, not inside it. Publishing first would let
		// inventory reserve stock for an order that then failed to persist — and until Phase 8's
		// outbox, this ordering is the best available answer: an order that exists without its
		// command is recoverable by retrying, whereas a hold without an order is not.
		saga.requestReservation(order);
		return order;
	}

	/**
	 * The customer or an operator cancels.
	 *
	 * <p>Now asks inventory to release rather than telling it to. The order moves to
	 * {@code CANCELLED} straight away because that is a decision this service is entitled to make on
	 * its own; the stock comes back when inventory gets round to the command.
	 */
	@Transactional
	public Order cancel(String orderNumber, String reason) {
		Order order = requireByNumber(orderNumber);

		if (order.getStatus() == OrderStatus.CANCELLED) {
			return order;
		}
		// Checked before anything is published. In particular there is deliberately no
		// PAYMENT_PENDING -> CANCELLED edge: a charge is in flight and has to be resolved rather
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
			saga.releaseInventory(order, reason == null ? "order cancelled" : reason);
		}
		history.save(order.transitionTo(OrderStatus.CANCELLED, reason == null ? "cancelled" : reason,
				CorrelationId.current()));
		return order;
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
			throw new DuplicateIdempotencyKeyException(idempotencyKey);
		}
		history.save(order.creationRecord(CorrelationId.current()));
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
