package com.flashcart.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.flashcart.common.error.ResourceNotFoundException;
import com.flashcart.order.client.CatalogClient;
import com.flashcart.order.client.InventoryClient;
import com.flashcart.order.client.InventoryRejectedException;
import com.flashcart.order.client.InventoryUnavailableException;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Controllable stand-ins for catalog and inventory.
 *
 * <p>The order service's interesting behaviour is what it does when a downstream says <em>no</em>,
 * or says nothing at all. Those cases are almost impossible to provoke against real services on
 * demand — you cannot ask a healthy inventory to time out — so the tests drive them through these
 * instead, and the real HTTP clients are exercised by the compose smoke test in CI.
 *
 * <p>Both fakes record what they were asked, because for the compensation paths "was inventory told
 * to release?" is the actual assertion, not a detail.
 */
@TestConfiguration
public class FakeDownstreams {

	@Bean
	@Primary
	FakeCatalog fakeCatalog() {
		return new FakeCatalog();
	}

	@Bean
	@Primary
	FakeInventory fakeInventory() {
		return new FakeInventory();
	}

	/** A catalog with whatever products a test puts in it. */
	public static class FakeCatalog implements CatalogClient {

		private final Map<String, PricedProduct> products = new ConcurrentHashMap<>();

		public void stock(String sku, String name, String price) {
			products.put(sku, new PricedProduct(sku, name, new BigDecimal(price), "USD", false));
		}

		public void stock(String sku, String name, String price, String currency) {
			products.put(sku, new PricedProduct(sku, name, new BigDecimal(price), currency, false));
		}

		public void clear() {
			products.clear();
		}

		@Override
		public PricedProduct priceOf(String sku) {
			PricedProduct product = products.get(sku);
			if (product == null) {
				throw ResourceNotFoundException.of("Product with SKU", sku);
			}
			return product;
		}
	}

	/** An inventory that can be told to accept, refuse, or go silent. */
	public static class FakeInventory implements InventoryClient {

		public enum Behaviour {
			/** Hold the stock and hand back a reservation. */
			ACCEPT,
			/** Refuse with a code, the way a real sold-out SKU would. */
			REJECT,
			/** Neither accept nor refuse — the timeout case, where the hold's state is unknown. */
			UNAVAILABLE
		}

		private volatile Behaviour behaviour = Behaviour.ACCEPT;
		private volatile String rejectionCode = "INSUFFICIENT_STOCK";
		private volatile Duration ttl = Duration.ofMinutes(15);
		private volatile boolean releaseUnavailable;

		private final List<ReserveCommand> reserves = new ArrayList<>();
		private final List<String> commits = new ArrayList<>();
		private final Map<String, String> releases = new ConcurrentHashMap<>();
		private final AtomicInteger releaseCalls = new AtomicInteger();

		public void reset() {
			behaviour = Behaviour.ACCEPT;
			rejectionCode = "INSUFFICIENT_STOCK";
			ttl = Duration.ofMinutes(15);
			releaseUnavailable = false;
			synchronized (reserves) {
				reserves.clear();
			}
			synchronized (commits) {
				commits.clear();
			}
			releases.clear();
			releaseCalls.set(0);
		}

		public void willAccept() {
			behaviour = Behaviour.ACCEPT;
		}

		public void willReject(String code) {
			behaviour = Behaviour.REJECT;
			rejectionCode = code;
		}

		public void willBeUnavailable() {
			behaviour = Behaviour.UNAVAILABLE;
		}

		/** Makes the hold lapse almost immediately, so expiry is testable without a real wait. */
		public void holdsFor(Duration duration) {
			this.ttl = duration;
		}

		public void releaseWillBeUnavailable(boolean unavailable) {
			this.releaseUnavailable = unavailable;
		}

		public List<ReserveCommand> reserves() {
			synchronized (reserves) {
				return List.copyOf(reserves);
			}
		}

		public boolean wasReleased(String reservationKey) {
			return releases.containsKey(reservationKey);
		}

		public String releaseReason(String reservationKey) {
			return releases.get(reservationKey);
		}

		public int releaseCallCount() {
			return releaseCalls.get();
		}

		public boolean wasCommitted(String reservationKey) {
			synchronized (commits) {
				return commits.contains(reservationKey);
			}
		}

		@Override
		public Reservation reserve(ReserveCommand command) {
			synchronized (reserves) {
				reserves.add(command);
			}
			return switch (behaviour) {
				case ACCEPT -> new Reservation(command.reservationKey(), "HELD", Instant.now().plus(ttl));
				case REJECT -> throw new InventoryRejectedException(rejectionCode,
						"Inventory refused: " + rejectionCode);
				case UNAVAILABLE -> throw new InventoryUnavailableException(
						"Inventory could not be reached while reserving", null);
			};
		}

		@Override
		public void commit(String reservationKey) {
			synchronized (commits) {
				commits.add(reservationKey);
			}
		}

		@Override
		public void release(String reservationKey, String reason) {
			releaseCalls.incrementAndGet();
			if (releaseUnavailable) {
				throw new InventoryUnavailableException("Inventory could not be reached while releasing",
						null);
			}
			releases.put(reservationKey, reason);
		}
	}
}
