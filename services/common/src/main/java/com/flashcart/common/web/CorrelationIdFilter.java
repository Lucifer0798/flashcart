package com.flashcart.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts an inbound correlation id or mints one, exposes it to logging via the MDC, and echoes it
 * back on the response.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so every later filter sees a populated MDC. The
 * gateway stamps the header on the way in, so all seven services log the same id for one checkout.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlationId = request.getHeader(CorrelationId.HEADER);
		if (!StringUtils.hasText(correlationId)) {
			correlationId = UUID.randomUUID().toString();
		}
		MDC.put(CorrelationId.MDC_KEY, correlationId);
		response.setHeader(CorrelationId.HEADER, correlationId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			// Servlet threads are pooled; a stale id here would mislabel the next request's logs.
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}
}
