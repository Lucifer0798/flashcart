package com.flashcart.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.flashcart.payment.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A provider that can be told what to do.
 *
 * <p>Real payment gateways are approximately always available and approximately always approve,
 * which makes them useless for exercising the paths that matter. Every interesting behaviour in this
 * platform — the compensation that releases stock on a decline, the reconciliation that must not
 * release it on a timeout — hangs off outcomes a healthy sandbox will not produce on demand.
 *
 * <p>So the outcome is chosen by the <strong>amount</strong>, which is the one field that travels
 * end to end without any test hook in the API:
 *
 * <ul>
 *   <li>an amount ending in {@code .13} is declined</li>
 *   <li>an amount ending in {@code .99} times out</li>
 *   <li>anything else is approved</li>
 * </ul>
 *
 * <p>Picking the amount rather than a magic customer id or a header means the compose stack can
 * demonstrate a declined checkout with nothing but a product price, and the trigger survives every
 * hop between the storefront and here.
 *
 * <p>Configurable global rates exist too, for load and failure-injection work in Phases 10 and 11.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

	private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final PaymentProperties properties;

	public SimulatedPaymentProvider(PaymentProperties properties) {
		this.properties = properties;
	}

	@Override
	public Outcome charge(String idempotencyKey, BigDecimal amount, String currency, String customerId) {
		int pence = amount.movePointRight(2).remainder(HUNDRED).abs().intValue();

		if (pence == properties.declineOnCents()) {
			log.info("Simulated decline for {} ({} {})", idempotencyKey, amount, currency);
			return Outcome.declined("CARD_DECLINED", "The card was declined by the issuer");
		}
		if (pence == properties.timeoutOnCents()) {
			log.info("Simulated provider timeout for {} ({} {})", idempotencyKey, amount, currency);
			throw new ProviderTimeoutException(
					"The payment provider did not respond within the timeout");
		}

		// A stand-in for the provider's own reference. Derived from nothing meaningful on purpose:
		// the point is that it is the provider's identifier, not ours.
		String reference = "sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		log.debug("Simulated approval for {} -> {}", idempotencyKey, reference);
		return Outcome.approved(reference);
	}
}
