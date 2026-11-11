package com.flashcart.user.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No more hashes at once than the cap. See ADR 0071. */
class BoundedPasswordEncoderTest {

	/** A stand-in for BCrypt that records how many callers are inside it at once, and holds each a while. */
	static final class CountingEncoder implements PasswordEncoder {

		final AtomicInteger inside = new AtomicInteger();
		final AtomicInteger mostAtOnce = new AtomicInteger();

		@Override
		public String encode(CharSequence rawPassword) {
			work();
			return "hashed:" + rawPassword;
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			work();
			return encodedPassword.equals("hashed:" + rawPassword);
		}

		private void work() {
			mostAtOnce.accumulateAndGet(inside.incrementAndGet(), Math::max);
			try {
				Thread.sleep(30);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			finally {
				inside.decrementAndGet();
			}
		}
	}

	@Test
	@DisplayName("forty callers at once are hashed no more than four at a time, and every answer is still right")
	void capsConcurrentHashing() throws Exception {
		CountingEncoder counting = new CountingEncoder();
		PasswordEncoder bounded = new BoundedPasswordEncoder(counting, 4);
		ExecutorService pool = Executors.newFixedThreadPool(40);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Boolean>> answers = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			String password = "password-" + i;
			boolean right = i % 2 == 0;
			answers.add(pool.submit(() -> {
				go.await();
				return bounded.matches(password, right ? "hashed:" + password : "hashed:wrong") == right;
			}));
			answers.add(pool.submit(() -> {
				go.await();
				return bounded.encode(password).equals("hashed:" + password);
			}));
		}
		go.countDown();
		for (Future<Boolean> answer : answers) {
			assertThat(answer.get(10, TimeUnit.SECONDS)).isTrue();
		}
		pool.shutdown();

		// Exactly four, not "at most four": a cap of four that let only one through would pass a weaker check
		// and serialise every sign-in.
		assertThat(counting.mostAtOnce.get()).isEqualTo(4);
	}

	@Test
	@DisplayName("works with real BCrypt, and a cap below one is refused rather than deadlocking every sign-in")
	void realBcryptAndASaneCap() {
		PasswordEncoder bounded = new BoundedPasswordEncoder(new BCryptPasswordEncoder(), 2);
		String hash = bounded.encode("correct horse");

		assertThat(bounded.matches("correct horse", hash)).isTrue();
		assertThat(bounded.matches("wrong horse", hash)).isFalse();
		assertThatThrownBy(() -> new BoundedPasswordEncoder(new BCryptPasswordEncoder(), 0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
