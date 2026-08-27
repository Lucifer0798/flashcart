package com.flashcart.catalog.service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.flashcart.catalog.domain.FlashSaleItem;
import com.flashcart.catalog.repository.FlashSaleItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "what does this product actually cost right now".
 *
 * <p>The single place discount rules live. Every surface — listing, detail, the order service when
 * it prices a basket — resolves through here, so a shopper can never see one price on the grid and
 * be charged another at checkout.
 */
@Service
@Transactional(readOnly = true)
public class PricingService {

	private final FlashSaleItemRepository flashSaleItems;
	private final Clock clock;

	public PricingService(FlashSaleItemRepository flashSaleItems, Clock clock) {
		this.flashSaleItems = flashSaleItems;
		this.clock = clock;
	}

	/**
	 * The live offer for each of {@code productIds} that has one, resolved in a single query.
	 *
	 * <p>Batched deliberately: pricing a 50-row page one product at a time is the N+1 that makes a
	 * listing slowest exactly when a sale makes it busiest.
	 */
	public Map<UUID, FlashSaleItem> liveOffersByProduct(Collection<UUID> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			return Map.of();
		}
		List<FlashSaleItem> offers = flashSaleItems.findLiveOffersForProducts(productIds, clock.instant());
		return offers.stream().collect(Collectors.toMap(
				item -> item.getProduct().getId(),
				Function.identity(),
				// A product can legitimately sit in two overlapping sales. The shopper gets the
				// better of the two — any other tie-break would be indefensible to explain.
				PricingService::cheaperOf));
	}

	public Optional<FlashSaleItem> liveOfferFor(UUID productId) {
		return Optional.ofNullable(liveOffersByProduct(List.of(productId)).get(productId));
	}

	private static FlashSaleItem cheaperOf(FlashSaleItem a, FlashSaleItem b) {
		return a.getSalePrice().compareTo(b.getSalePrice()) <= 0 ? a : b;
	}
}
