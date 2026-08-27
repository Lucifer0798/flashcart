package com.flashcart.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the context still starts. Thin, but it is the test that catches a bad bean definition or a
 * broken auto-configuration import the moment it is introduced.
 */
@SpringBootTest
class UserApplicationTests {

	@Test
	void contextLoads() {
	}
}
