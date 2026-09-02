package com.flashcart.shipping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.flashcart.common.event.DomainEvent;
import com.flashcart.common.event.EventPublisher;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Captures what would have gone on the bus.
 *
 * <p>These tests are about what this service <em>decides</em>, and a decision is only observable
 * here as a published message. Running a real broker for that would test Kafka's plumbing over and
 * over while making every assertion wait on a poll; the round trip through a real broker is proved
 * once, separately, by the Kafka integration test and by the compose smoke test in CI.
 */
@TestConfiguration
public class RecordingEventPublisher {

	@Bean
	@Primary
	Recorder recordingEventPublisher() {
		return new Recorder();
	}

	public static class Recorder implements EventPublisher {

		private final List<Published> published = new ArrayList<>();

		public record Published(String topic, DomainEvent message) {
		}

		@Override
		public synchronized void publish(String topic, DomainEvent message) {
			published.add(new Published(topic, message));
		}

		public synchronized void clear() {
			published.clear();
		}

		public synchronized List<Published> all() {
			return List.copyOf(published);
		}

		/** The first message of this type, if one was published. */
		public synchronized <T extends DomainEvent> Optional<T> first(Class<T> type) {
			return published.stream()
					.map(Published::message)
					.filter(type::isInstance)
					.map(type::cast)
					.findFirst();
		}

		public synchronized <T extends DomainEvent> T require(Class<T> type) {
			return first(type).orElseThrow(() -> new AssertionError(
					"Expected a " + type.getSimpleName() + " to have been published, but saw: "
							+ published.stream().map(p -> p.message().eventType()).toList()));
		}

		public synchronized long countOf(Class<? extends DomainEvent> type) {
			return published.stream().map(Published::message).filter(type::isInstance).count();
		}

		public synchronized boolean published(Class<? extends DomainEvent> type) {
			return first(type).isPresent();
		}
	}
}
