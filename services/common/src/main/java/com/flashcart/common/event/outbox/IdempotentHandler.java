package com.flashcart.common.event.outbox;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.web.CorrelationId;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a message handler at most once, and with the originating request's correlation id restored.
 *
 * <p>Both halves matter and both were previously repeated in every listener.
 *
 * <p><strong>At most once.</strong> The claim and the handler run in one transaction, so they commit
 * together: a handler that fails does not leave the message marked processed, and one that succeeds
 * cannot lose its claim. Doing the two separately would reopen, on the consuming side, exactly the
 * gap the outbox closes on the producing side.
 *
 * <p><strong>The correlation id.</strong> A Kafka consumer thread never saw the HTTP request that
 * started the checkout, so without restoring it every log line the handler writes is unattributable
 * and a checkout stops being traceable the moment it crosses the bus.
 */
public class IdempotentHandler {

	private final ProcessedEvents processedEvents;
	private final TransactionTemplate transactions;

	public IdempotentHandler(ProcessedEvents processedEvents,
			PlatformTransactionManager transactionManager) {
		this.processedEvents = processedEvents;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * @param consumer names the logical consumer, so several services can each process the same
	 *                 message exactly once
	 * @return true if the handler ran, false if the message was a duplicate and was skipped
	 */
	public boolean handle(DomainEvent message, String consumer, Runnable work) {
		String previous = MDC.get(CorrelationId.MDC_KEY);
		if (message.correlationId() != null) {
			MDC.put(CorrelationId.MDC_KEY, message.correlationId());
		}
		try {
			return Boolean.TRUE.equals(transactions.execute(status -> {
				if (!processedEvents.claim(message.eventId(), consumer)) {
					return false;
				}
				work.run();
				return true;
			}));
		}
		finally {
			if (previous == null) {
				MDC.remove(CorrelationId.MDC_KEY);
			}
			else {
				MDC.put(CorrelationId.MDC_KEY, previous);
			}
		}
	}
}
