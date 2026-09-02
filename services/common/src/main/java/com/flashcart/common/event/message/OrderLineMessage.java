package com.flashcart.common.event.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One line of an order as it travels on the bus.
 *
 * <p>Carries only what a downstream service needs to act — a SKU and a quantity. Notably not the
 * price: inventory has no business knowing it, and payment is told the order total rather than
 * recomputing it from lines it would have to trust.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLineMessage(String sku, int quantity) {
}
