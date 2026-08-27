package com.flashcart.common.web;

import org.slf4j.MDC;

/** Names and accessors for the id that stitches one user request together across every service. */
public final class CorrelationId {

	/** Inbound and outbound HTTP header. */
	public static final String HEADER = "X-Correlation-Id";

	/** SLF4J MDC key, referenced by the logging pattern in every service's application.yml. */
	public static final String MDC_KEY = "correlationId";

	private CorrelationId() {
	}

	/** The correlation id for the request being handled on this thread, or {@code null} outside one. */
	public static String current() {
		return MDC.get(MDC_KEY);
	}
}
