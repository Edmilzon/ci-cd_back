package com.space.cdci;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class CdciApplicationTests {
	@Autowired
	private WebApplicationContext applicationContext;
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
				.apply(springSecurity())
				.build();
	}

	@Test
	void contextLoads() {
	}

	@Test
	void csrfEndpointTokenAuthorizesRegistration() throws Exception {
		MvcResult csrfResponse = mockMvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isOk())
				.andReturn();
		String csrfToken = objectMapper.readTree(csrfResponse.getResponse().getContentAsString())
				.get("token").asString();
		Assertions.assertEquals(csrfResponse.getResponse().getCookie("XSRF-TOKEN").getValue(), csrfToken);

		String email = "csrf-" + UUID.randomUUID() + "@example.invalid";
		String body = "{\"email\":\"" + email + "\",\"fullName\":\"CSRF integration\",\"password\":\"Valid-Smoke-Password_123!\"}";
		mockMvc.perform(post("/api/auth/register")
				.cookie(csrfResponse.getResponse().getCookie("XSRF-TOKEN"))
				.header("X-XSRF-TOKEN", csrfToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated());
	}

}
