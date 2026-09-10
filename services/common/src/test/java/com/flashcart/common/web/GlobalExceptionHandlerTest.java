package com.flashcart.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The handler decides what a failure looks like to a caller, and the difference between 400 and 500
 * is the difference between "you sent the wrong thing" and "we are broken". Getting it wrong sends
 * whoever is on call to look at the wrong system.
 *
 * <p>Driven through a standalone MockMvc rather than a running service, so these are the actual
 * exceptions Spring's argument resolvers raise, without a container to start.
 */
class GlobalExceptionHandlerTest {

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@Test
	@DisplayName("a missing required parameter is the caller's mistake, not a server fault")
	void missingParameterIsBadRequest() throws Exception {
		mvc.perform(get("/probe/needs-param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("and it names the parameter, since it knows which one")
	void missingParameterNamesTheField() throws Exception {
		mvc.perform(get("/probe/needs-param"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("customerId"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("is required"));
	}

	@Test
	@DisplayName("supplying it is not an error")
	void presentParameterSucceeds() throws Exception {
		mvc.perform(get("/probe/needs-param").param("customerId", "c1"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a path variable the mapping cannot supply stays a 500 -- that one really is our bug")
	void missingPathVariableIsStillServerError() throws Exception {
		mvc.perform(get("/probe/no-such-variable"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	@Test
	@DisplayName("an unreadable body is still MALFORMED_REQUEST, not a validation failure")
	void unreadableBodyIsUnchanged() throws Exception {
		mvc.perform(post("/probe/needs-body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ this is not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	@DisplayName("a parameter of the wrong type is still MALFORMED_REQUEST")
	void typeMismatchIsUnchanged() throws Exception {
		mvc.perform(get("/probe/needs-number").param("quantity", "not-a-number"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@RestController
	static class ProbeController {

		/** The shape of PaymentController.forCustomer and ShipmentController.forCustomer. */
		@GetMapping("/probe/needs-param")
		String needsParam(@RequestParam String customerId) {
			return customerId;
		}

		@GetMapping("/probe/needs-number")
		String needsNumber(@RequestParam int quantity) {
			return String.valueOf(quantity);
		}

		/** No {@code {id}} in the mapping, so the variable can never be supplied. */
		@GetMapping("/probe/no-such-variable")
		String missingPathVariable(@PathVariable String id) {
			return id;
		}

		@PostMapping("/probe/needs-body")
		String needsBody(@RequestBody Payload payload) {
			return payload.name();
		}

		record Payload(String name) {
		}
	}
}
