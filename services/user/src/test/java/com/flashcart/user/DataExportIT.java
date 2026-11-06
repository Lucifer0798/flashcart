package com.flashcart.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A customer downloading what the user service holds about them. See ADR 0066.
 *
 * <p>The tests that matter most here are about what is not in the file. They look at the raw response, so a
 * field added to a section later is caught without anybody remembering to add it to a list of bad names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "flashcart.password-reset.limit.client.requests=1000")
@AutoConfigureTestRestTemplate
class DataExportIT {

	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	private static final String PASSWORD = "the-original-password";
	private static final String PATH = "/api/v1/user/_export/mine";
	private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

	static class RecordingMailSender extends JavaMailSenderImpl {

		final List<SimpleMailMessage> sent = new ArrayList<>();
		volatile String refuseWith;

		@Override
		public synchronized void send(SimpleMailMessage... messages) {
			if (refuseWith != null) {
				throw new org.springframework.mail.MailSendException(refuseWith);
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

	@BeforeEach
	void reset() {
		mail.sent.clear();
		mail.refuseWith = null;
		jdbc.update("delete from notices");
	}

	@Test
	@DisplayName("the file holds the caller's profile, addresses, sessions, resets and emails, and arrives as a file")
	@SuppressWarnings("unchecked")
	void theFileHoldsWhatIsKept() {
		Shopper shopper = register("Export Shopper");
		assertThat(rest.exchange("/api/v1/users/me/addresses", HttpMethod.POST, new HttpEntity<>(Map.of("label", "Home",
				"line1", "1 High Street", "city", "Leeds", "postcode", "LS1 1AA", "country", "GB", "makeDefault", true),
				shopper.headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		askToReset(shopper.email());
		sender.sendDue();

		ResponseEntity<Map> export = export(shopper.headers());

		assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.isEqualTo("attachment; filename=\"flashcart-user-export.json\"");
		Map<String, Object> file = export.getBody();
		assertThat(file).containsEntry("service", "user").containsEntry("customerId", shopper.id())
				.containsKey("exportedAt");

		Map<String, Object> profile = (Map<String, Object>) file.get("profile");
		assertThat(profile).containsEntry("email", shopper.email()).containsEntry("displayName", "Export Shopper");
		assertThat((List<Map<String, Object>>) profile.get("addresses")).singleElement()
				.satisfies(address -> assertThat(address).containsEntry("line1", "1 High Street"));

		assertThat((List<Map<String, Object>>) file.get("sessions")).singleElement()
				.satisfies(session -> assertThat(session).containsKeys("session", "issuedAt", "expiresAt"));
		assertThat((List<Map<String, Object>>) file.get("passwordResets")).singleElement()
				.satisfies(reset -> assertThat(reset).containsKeys("requestedAt", "expiresAt", "usedAt"));
		assertThat((List<Map<String, Object>>) file.get("emails")).singleElement().satisfies(email -> {
			assertThat(email).containsEntry("subject", "Reset your FlashCart password")
					.containsEntry("status", "SENT");
			assertThat(email.get("sentAt")).isNotNull();
		});
	}

	@Test
	@DisplayName("no password hash and no token hash is in the file, in any section")
	void noCredentialIsInTheFile() {
		Shopper shopper = register("Credential Shopper");
		askToReset(shopper.email());
		sender.sendDue();

		String passwordHash = jdbc.queryForObject("select password_hash from users where id = ?::uuid", String.class,
				shopper.id());
		List<String> refreshHashes = jdbc.queryForList("select token_hash from refresh_tokens where user_id = ?::uuid",
				String.class, shopper.id());
		String resetHash = jdbc.queryForObject("select token_hash from password_resets where user_id = ?::uuid",
				String.class, shopper.id());
		// There to be found: an assertion that a null is absent would prove nothing.
		assertThat(refreshHashes).isNotEmpty();
		assertThat(resetHash).isNotBlank();

		String raw = exportRaw(shopper.headers());

		assertThat(raw).doesNotContain(passwordHash).doesNotContain(resetHash).doesNotContainIgnoringCase("hash");
		refreshHashes.forEach(hash -> assertThat(raw).doesNotContain(hash));
	}

	@Test
	@DisplayName("a reset email is in the file without its link, so the file is not a way to take over the account")
	void aResetEmailHasNoWorkingLink() {
		Shopper shopper = register("Link Shopper");
		askToReset(shopper.email());
		sender.sendDue();
		// The one that really went out, to look for in the file.
		assertThat(mail.sent).hasSize(1);
		Matcher matcher = TOKEN.matcher(mail.sent.get(0).getText());
		assertThat(matcher.find()).isTrue();
		String token = matcher.group(1);

		String raw = exportRaw(shopper.headers());

		assertThat(raw).contains("Reset your FlashCart password");
		assertThat(raw).doesNotContain(token).doesNotContain("token=");
	}

	@Test
	@DisplayName("the mail server's errors and retry counts stay out: they are about our mail, not the customer")
	void mailOperationsStayOut() {
		Shopper shopper = register("Mail Shopper");
		askToReset(shopper.email());
		mail.refuseWith = "550 relay refused by mx-internal-7";
		sender.sendDue();
		// A recorded failure to leave out: without one, nothing below can fail.
		assertThat(jdbc.queryForObject("select last_error from notices where customer_id = ?", String.class, shopper.id()))
				.contains("mx-internal-7");

		String raw = exportRaw(shopper.headers());

		assertThat(raw).contains("Reset your FlashCart password");
		assertThat(raw).doesNotContain("mx-internal-7").doesNotContain("lastError").doesNotContain("attempts")
				.doesNotContain("notice_key").doesNotContain("nextAttemptAt");
	}

	@Test
	@DisplayName("a role change shows what and when, not who made it or the reason staff wrote down")
	@SuppressWarnings("unchecked")
	void roleChangesSayWhatNotWho() {
		Shopper shopper = register("Staff Shopper");
		jdbc.update("insert into role_grants (user_id, role, action, reason, granted_by) values (?::uuid, 'SUPPORT', "
				+ "'GRANT', 'Requested by Dana in ticket 4471', 'admin-sam')", shopper.id());

		ResponseEntity<Map> export = export(shopper.headers());

		assertThat((List<Map<String, Object>>) export.getBody().get("roleChanges")).singleElement()
				.satisfies(change -> assertThat(change).containsEntry("role", "SUPPORT").containsEntry("action", "GRANT")
						.containsOnlyKeys("role", "action", "at"));
		assertThat(exportRaw(shopper.headers())).doesNotContain("Dana").doesNotContain("admin-sam");
	}

	@Test
	@DisplayName("nobody else's rows are in the file, and the request cannot ask for them")
	void onlyTheCallersOwn() {
		Shopper mine = register("Mine");
		Shopper theirs = register("Theirs");
		askToReset(theirs.email());
		sender.sendDue();
		jdbc.update("insert into operator_access_log (operator_id, action, customer_id) values ('support-1', 'READ_NOTICES', ?)",
				theirs.id());

		ResponseEntity<Map> asked = rest.exchange(PATH + "?customerId=" + theirs.id(), HttpMethod.GET,
				new HttpEntity<>(mine.headers()), Map.class);

		assertThat(asked.getBody()).containsEntry("customerId", mine.id());
		assertThat(asked.getBody().toString()).doesNotContain(theirs.id()).doesNotContain(theirs.email())
				.doesNotContain("Theirs");
		assertThat((List<?>) asked.getBody().get("emails")).isEmpty();
		assertThat((List<?>) asked.getBody().get("passwordResets")).isEmpty();
		assertThat((List<?>) asked.getBody().get("staffAccess")).isEmpty();
	}

	@Test
	@DisplayName("the file says when staff looked, and at what, and downloading it is not itself recorded as staff")
	@SuppressWarnings("unchecked")
	void staffAccessIsThereAndOwnReadIsNot() {
		Shopper shopper = register("Watched Shopper");
		jdbc.update("insert into operator_access_log (operator_id, action, customer_id) values ('support-1', 'READ_NOTICES', ?)",
				shopper.id());

		ResponseEntity<Map> export = export(shopper.headers());

		assertThat((List<Map<String, Object>>) export.getBody().get("staffAccess")).singleElement()
				.satisfies(entry -> assertThat(entry).containsEntry("action", "READ_NOTICES")
						.containsOnlyKeys("action", "description", "resourceId", "readAt"));
		assertThat(exportRaw(shopper.headers())).doesNotContain("support-1");
		assertThat(jdbc.queryForObject("select count(*) from operator_access_log where customer_id = ?", Integer.class,
				shopper.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("a request without a token that verifies is refused")
	void refusedWithoutAToken() {
		assertThat(rest.getForEntity(PATH, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		HttpHeaders forged = new HttpHeaders();
		forged.setBearerAuth("not-a-real-token");
		assertThat(export(forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a closed account has nothing identifying left to export, even with a token still in its hand")
	void aClosedAccountGetsNothing() {
		Shopper shopper = register("Closing Shopper");
		assertThat(rest.exchange("/api/v1/users/me/close", HttpMethod.POST,
				new HttpEntity<>(Map.of("password", PASSWORD), shopper.headers()), Void.class).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(export(shopper.headers()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// --- helpers --------------------------------------------------------------------------------------

	private record Shopper(String id, String email, HttpHeaders headers) {
	}

	@SuppressWarnings("unchecked")
	private Shopper register(String name) {
		String email = "export-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		Map<String, Object> created = rest.postForObject("/api/v1/users",
				Map.of("email", email, "password", PASSWORD, "displayName", name), Map.class);
		Map<String, Object> session = rest.postForObject("/api/v1/users/sessions",
				Map.of("email", email, "password", PASSWORD), Map.class);
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth((String) session.get("accessToken"));
		return new Shopper((String) created.get("id"), email, headers);
	}

	private void askToReset(String email) {
		assertThat(rest.postForEntity("/api/v1/users/password-reset", Map.of("email", email), Map.class).getStatusCode())
				.isEqualTo(HttpStatus.ACCEPTED);
	}

	private ResponseEntity<Map> export(HttpHeaders headers) {
		return rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
	}

	private String exportRaw(HttpHeaders headers) {
		ResponseEntity<String> raw = rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
		assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
		return raw.getBody();
	}
}
