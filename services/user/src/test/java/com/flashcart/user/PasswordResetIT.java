package com.flashcart.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flashcart.common.security.AccessTokens;
import com.flashcart.user.notice.NoticeSender;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/** Resetting a forgotten password by email. See ADR 0057. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PasswordResetIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	private static final String OLD = "the-original-password";
	private static final String NEW = "a-brand-new-password";
	private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

	static class RecordingMailSender extends JavaMailSenderImpl {

		final List<SimpleMailMessage> sent = new ArrayList<>();
		volatile boolean down;

		@Override
		public synchronized void send(SimpleMailMessage... messages) {
			if (down) {
				throw new MailSendException("Connection refused: mail server is down (test)");
			}
			sent.addAll(List.of(messages));
		}

		@Override
		public void send(MimeMessage... messages) {
			throw new UnsupportedOperationException("notices are plain text");
		}
	}

	@TestConfiguration
	static class Mail {

		@Bean
		@Primary
		RecordingMailSender recordingMailSender() {
			return new RecordingMailSender();
		}
	}

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NoticeSender sender;

	@Autowired
	private RecordingMailSender mail;

	@Autowired
	private AccessTokens tokens;

	@BeforeEach
	void reset() {
		mail.sent.clear();
		mail.down = false;
		jdbc.update("delete from notices");
	}

	@Test
	@DisplayName("a reset link sets a new password, once, and the old one stops working")
	void resettingAPassword() {
		String email = register();

		assertThat(request(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		SimpleMailMessage message = mail.sent.get(0);
		assertThat(message.getTo()).containsExactly(email);
		assertThat(message.getSubject()).isEqualTo("Reset your FlashCart password");
		String token = tokenIn(message);

		assertThat(confirm(token, NEW).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(signIn(email, NEW)).isEqualTo(HttpStatus.OK);
		assertThat(signIn(email, OLD)).isEqualTo(HttpStatus.UNAUTHORIZED);

		// Once only.
		ResponseEntity<Map> again = confirm(token, "yet-another-password");
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(again.getBody()).containsEntry("code", "RESET_TOKEN_INVALID");
		assertThat(signIn(email, NEW)).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("asking for an address with no account looks exactly the same, and sends nothing")
	void askingRevealsNothing() {
		String email = register();

		ResponseEntity<Map> known = request(email);
		ResponseEntity<Map> unknown = request("nobody-" + UUID.randomUUID() + "@example.test");
		sender.sendDue();

		assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(unknown.getBody()).isEqualTo(known.getBody());
		assertThat(mail.sent).hasSize(1);
		assertThat(mail.sent.get(0).getTo()).containsExactly(email);
	}

	@Test
	@DisplayName("the token is never stored: not in the resets table, not in the queue, not in support's view")
	void theTokenIsNeverStored() {
		String email = register();
		String id = idOf(email);
		request(email);
		sender.sendDue();
		String token = tokenIn(mail.sent.get(0));

		String hash = jdbc.queryForObject("select token_hash from password_resets where user_id = ?::uuid", String.class, id);
		assertThat(hash).hasSize(64).isNotEqualTo(token).doesNotContain(token);
		assertThat(jdbc.queryForObject("select payload::text from notices where customer_id = ?", String.class, id))
				.doesNotContain(token).doesNotContain("token=");

		HttpHeaders support = new HttpHeaders();
		support.setBearerAuth(tokens.issue("support-1", "support@flashcart.test", List.of(AccessTokens.SUPPORT)));
		ResponseEntity<String> seen = rest.exchange("/api/v1/user/_notices?customerId=" + id, HttpMethod.GET,
				new HttpEntity<>(support), String.class);
		assertThat(seen.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(seen.getBody()).contains("Reset your FlashCart password").contains("It is not kept anywhere")
				.doesNotContain(token).doesNotContain("token=");
	}

	@Test
	@DisplayName("a link past its thirty minutes does not work, and one not yet sent by then is never sent")
	void expiredLinks() {
		String email = register();
		request(email);
		sender.sendDue();
		String token = tokenIn(mail.sent.get(0));
		jdbc.update("update password_resets set expires_at = now() - interval '1 second' where user_id = ?::uuid",
				idOf(email));

		assertThat(confirm(token, NEW).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(signIn(email, OLD)).isEqualTo(HttpStatus.OK);

		// A second account, whose email is held up until its link has lapsed.
		String late = register();
		mail.down = true;
		request(late);
		sender.sendDue();
		jdbc.update("update password_resets set expires_at = now() - interval '1 second' where user_id = ?::uuid",
				idOf(late));
		jdbc.update("update notices set next_attempt_at = now(), send_before = now() + interval '1 hour' "
				+ "where customer_id = ?", idOf(late));
		mail.down = false;
		mail.sent.clear();
		sender.sendDue();
		assertThat(mail.sent).isEmpty();
	}

	@Test
	@DisplayName("asking again within a minute sends nothing more, so the form cannot flood an inbox")
	void notTwiceInAMinute() {
		String email = register();

		request(email);
		request(email);
		request(email);
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(jdbc.queryForObject("select count(*) from password_resets where user_id = ?::uuid", Integer.class,
				idOf(email))).isEqualTo(1);
	}

	@Test
	@DisplayName("a send the mail server refused is retried with a fresh token, and only the one delivered works")
	void retriedSendsMintAgain() {
		String email = register();
		String id = idOf(email);
		request(email);
		mail.down = true;
		sender.sendDue();
		String firstHash = jdbc.queryForObject("select token_hash from password_resets where user_id = ?::uuid",
				String.class, id);

		mail.down = false;
		jdbc.update("update notices set next_attempt_at = now() where customer_id = ?", id);
		sender.sendDue();

		assertThat(mail.sent).hasSize(1);
		assertThat(jdbc.queryForObject("select token_hash from password_resets where user_id = ?::uuid", String.class, id))
				.isNotEqualTo(firstHash);
		assertThat(confirm(tokenIn(mail.sent.get(0)), NEW).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	@DisplayName("using one link cancels every other link still waiting in the inbox")
	void usingOneCancelsTheRest() {
		String email = register();
		String id = idOf(email);
		request(email);
		sender.sendDue();
		String first = tokenIn(mail.sent.get(0));
		// As if a minute had passed, so a second request is honoured.
		jdbc.update("update password_resets set requested_at = now() - interval '2 minutes' where user_id = ?::uuid", id);
		request(email);
		sender.sendDue();
		String second = tokenIn(mail.sent.get(1));

		assertThat(confirm(second, NEW).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(confirm(first, "a-third-password-here").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(signIn(email, NEW)).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a closed account gets no reset, and a made-up token or a short password is refused")
	void refusals() {
		String email = register();
		String id = idOf(email);
		jdbc.update("""
				update users set status = 'CLOSED', closed_at = now(), roles = '',
				       email = 'closed-' || id || '@closed.invalid' where id = ?::uuid""", id);

		request(email);
		sender.sendDue();
		assertThat(mail.sent).isEmpty();

		// A closed account's email changed, so that lookup finds nobody. A disabled one keeps its address,
		// and must be refused for its status.
		String disabled = register();
		jdbc.update("update users set status = 'DISABLED' where email = ?", disabled);
		request(disabled);
		sender.sendDue();
		assertThat(mail.sent).isEmpty();

		// A link already in the inbox stops working once the account closes.
		String closing = register();
		request(closing);
		sender.sendDue();
		String mailed = tokenIn(mail.sent.get(0));
		jdbc.update("""
				update users set status = 'CLOSED', closed_at = now(), roles = '',
				       email = 'closed-' || id || '@closed.invalid' where email = ?""", closing);
		assertThat(confirm(mailed, NEW).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		mail.sent.clear();

		assertThat(confirm("not-a-real-token", NEW).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		String other = register();
		request(other);
		sender.sendDue();
		assertThat(confirm(tokenIn(mail.sent.get(0)), "short").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		// The link was not spent on a password that was refused.
		assertThat(confirm(tokenIn(mail.sent.get(0)), NEW).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	// --- helpers ------------------------------------------------------------------------------------

	private String register() {
		String email = "forgetful-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		assertThat(rest.postForEntity("/api/v1/users", Map.of("email", email, "password", OLD, "displayName", "Forgetful"),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return email;
	}

	private String idOf(String email) {
		return jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
	}

	private ResponseEntity<Map> request(String email) {
		return rest.postForEntity("/api/v1/users/password-reset", Map.of("email", email), Map.class);
	}

	private ResponseEntity<Map> confirm(String token, String newPassword) {
		return rest.postForEntity("/api/v1/users/password-reset/confirm",
				Map.of("token", token, "newPassword", newPassword), Map.class);
	}

	private HttpStatus signIn(String email, String password) {
		return HttpStatus.valueOf(rest.postForEntity("/api/v1/users/sessions", Map.of("email", email, "password", password),
				Map.class).getStatusCode().value());
	}

	private static String tokenIn(SimpleMailMessage message) {
		Matcher matcher = TOKEN.matcher(message.getText());
		assertThat(matcher.find()).as("a reset link in: %s", message.getText()).isTrue();
		return matcher.group(1);
	}
}
