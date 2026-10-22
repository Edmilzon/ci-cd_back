package com.space.cdci.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class MfaFlowIntegrationTest {
	private static final String PASSWORD = "Valid-Smoke-Password_123!";

	@Autowired private WebApplicationContext applicationContext;
	@Autowired private ObjectMapper objectMapper;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
				.apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
				.build();
	}

	@Test
	void requiredMfaEnrollmentCompletesLoginAndRecoveryCodesCanOnlyBeUsedOnce() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		String csrf = json(csrfResult).get("token").asString();
		var cookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		String email = "mfa-" + UUID.randomUUID() + "@example.invalid";
		postJson("/api/auth/register", objectMapper.writeValueAsString(java.util.Map.of(
				"email", email, "fullName", "MFA integration", "password", PASSWORD)), null, cookie, csrf)
				.andExpect(status().isCreated());

		MvcResult passwordLogin = mockMvc.perform(post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", email).param("password", PASSWORD))
			.andExpect(status().isOk()).andReturn();
		assertTrue(json(passwordLogin).get("mfaRequired").asBoolean());
		assertTrue(json(passwordLogin).get("mfaSetupRequired").asBoolean());
		MockHttpSession session = (MockHttpSession) passwordLogin.getRequest().getSession(false);

		MvcResult setup = postJson("/api/auth/mfa/setup", objectMapper.writeValueAsString(java.util.Map.of("currentPassword", PASSWORD)), session, cookie, csrf)
				.andExpect(status().isOk()).andReturn();
		String secret = json(setup).get("secret").asString();
		String enrollmentCode = Totp.codeAt(secret, Instant.now());
		MvcResult confirmed = postJson("/api/auth/mfa/confirm", objectMapper.writeValueAsString(java.util.Map.of("code", enrollmentCode)), session, cookie, csrf)
				.andExpect(status().isOk()).andReturn();
		assertEquals(10, json(confirmed).get("recoveryCodes").size());
		String recoveryCode = json(confirmed).get("recoveryCodes").get(0).asString();
		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

		postEmpty("/api/auth/logout", session, cookie, csrf).andExpect(status().isNoContent());
		MvcResult secondPasswordLogin = mockMvc.perform(post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", email).param("password", PASSWORD))
			.andExpect(status().isOk()).andReturn();
		assertTrue(json(secondPasswordLogin).get("mfaRequired").asBoolean());
		assertTrue(!json(secondPasswordLogin).get("mfaSetupRequired").asBoolean());
		MockHttpSession secondSession = (MockHttpSession) secondPasswordLogin.getRequest().getSession(false);
		MvcResult recoveryLogin = postJson("/api/auth/mfa/login", objectMapper.writeValueAsString(java.util.Map.of("code", recoveryCode)), secondSession, cookie, csrf)
				.andExpect(status().isOk()).andReturn();
		assertTrue(json(recoveryLogin).get("authenticated").asBoolean());
		mockMvc.perform(get("/api/auth/me").session(secondSession)).andExpect(status().isOk());

		postEmpty("/api/auth/logout", secondSession, cookie, csrf).andExpect(status().isNoContent());
		MvcResult recoveryReplayChallenge = mockMvc.perform(post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", email).param("password", PASSWORD))
			.andExpect(status().isOk()).andReturn();
		MockHttpSession recoveryReplaySession = (MockHttpSession) recoveryReplayChallenge.getRequest().getSession(false);
		postJson("/api/auth/mfa/login", objectMapper.writeValueAsString(java.util.Map.of("code", recoveryCode)), recoveryReplaySession, cookie, csrf)
				.andExpect(status().isUnauthorized());
		postJson("/api/auth/mfa/login", objectMapper.writeValueAsString(java.util.Map.of("code", json(confirmed).get("recoveryCodes").get(1).asString())), recoveryReplaySession, cookie, csrf)
				.andExpect(status().isOk());
		secondSession = recoveryReplaySession;
		MvcResult mfaStatus = mockMvc.perform(get("/api/auth/mfa/status").session(secondSession)).andExpect(status().isOk()).andReturn();
		assertEquals(8, json(mfaStatus).get("recoveryCodesRemaining").asInt());

		MvcResult rotatedCodes = postJson("/api/auth/mfa/recovery-codes", objectMapper.writeValueAsString(java.util.Map.of(
				"currentPassword", PASSWORD, "code", json(confirmed).get("recoveryCodes").get(2).asString())), secondSession, cookie, csrf)
			.andExpect(status().isOk()).andReturn();
		assertEquals(10, json(rotatedCodes).get("recoveryCodes").size());
		String currentRecoveryCode = json(rotatedCodes).get("recoveryCodes").get(0).asString();

		MvcResult thirdPasswordLogin = mockMvc.perform(post("/api/auth/login").cookie(cookie).header("X-XSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", email).param("password", PASSWORD))
			.andExpect(status().isOk()).andReturn();
		MockHttpSession thirdSession = (MockHttpSession) thirdPasswordLogin.getRequest().getSession(false);
		postJson("/api/auth/mfa/login", objectMapper.writeValueAsString(java.util.Map.of("code", currentRecoveryCode)), thirdSession, cookie, csrf)
				.andExpect(status().isOk());

		postJson("/api/auth/mfa/disable", objectMapper.writeValueAsString(java.util.Map.of(
				"currentPassword", PASSWORD, "code", json(rotatedCodes).get("recoveryCodes").get(1).asString())), secondSession, cookie, csrf)
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/auth/mfa/status").session(secondSession)).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.enabled").value(false));
		mockMvc.perform(get("/api/auth/me").session(secondSession)).andExpect(status().isOk());

		postEmpty("/api/auth/logout", secondSession, cookie, csrf).andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, MockHttpSession session,
			jakarta.servlet.http.Cookie cookie, String csrf) throws Exception {
		MockHttpServletRequestBuilder request = post(path).cookie(cookie).header("X-XSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_JSON).content(body);
		if (session != null) request.session(session);
		return mockMvc.perform(request);
	}

	private org.springframework.test.web.servlet.ResultActions postEmpty(String path, MockHttpSession session,
			jakarta.servlet.http.Cookie cookie, String csrf) throws Exception {
		return mockMvc.perform(post(path).session(session).cookie(cookie).header("X-XSRF-TOKEN", csrf));
	}

	private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
}
