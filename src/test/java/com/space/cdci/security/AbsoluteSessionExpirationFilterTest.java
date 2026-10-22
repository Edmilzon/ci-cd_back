package com.space.cdci.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.space.cdci.identity.MfaService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import jakarta.servlet.FilterChain;

class AbsoluteSessionExpirationFilterTest {
	@AfterEach
	void clearContext() { SecurityContextHolder.clearContext(); }

	@Test
	void invalidatesSessionWhenAbsoluteLifetimeIsReached() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
		MockHttpSession session = spy(new MockHttpSession());
		session.setAttribute(MfaService.AUTHENTICATED_AT_ATTRIBUTE, Instant.now().minus(Duration.ofHours(13)).toEpochMilli());
		request.setSession(session);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user", null, java.util.List.of()));
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletResponse response = new MockHttpServletResponse();
		new AbsoluteSessionExpirationFilter(Duration.ofHours(12)).doFilter(request, response, chain);

		verify(session).invalidate();
		verify(chain).doFilter(request, response);
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}
}
