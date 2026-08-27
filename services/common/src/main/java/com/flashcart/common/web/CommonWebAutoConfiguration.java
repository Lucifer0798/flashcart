package com.flashcart.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Wires the shared servlet-side web plumbing into every FlashCart service that has an MVC stack.
 *
 * <p>Auto-configuration rather than component scanning: each service scans only its own package, and
 * this way the reactive gateway — which depends on the same module — silently skips all of it
 * instead of failing on a missing {@code DispatcherServlet}.
 */
@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

	@Bean
	public CorrelationIdFilter correlationIdFilter() {
		return new CorrelationIdFilter();
	}

	@Bean
	public GlobalExceptionHandler globalExceptionHandler() {
		return new GlobalExceptionHandler();
	}
}
