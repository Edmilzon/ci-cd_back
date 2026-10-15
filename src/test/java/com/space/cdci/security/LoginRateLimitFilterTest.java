package com.space.cdci.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.space.cdci.identity.AuthRequestRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import tools.jackson.databind.ObjectMapper;

class LoginRateLimitFilterTest {
	@Test
	void returnsRetryAfterWhenLoginRateIsExceeded() throws Exception {
		AuthRequestRateLimiter limiter = mock(AuthRequestRateLimiter.class);
		when(limiter.allow("login-ip", "203.0.113.5", 60, Duration.ofMinutes(15))).thenReturn(false);
		when(limiter.allow("login-email", "user@example.com", 10, Duration.ofMinutes(15))).thenReturn(true);
		LoginRateLimitFilter filter = new LoginRateLimitFilter(limiter, new ObjectMapper());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
		request.setServletPath("/api/auth/login");
		request.setRemoteAddr("203.0.113.5");
		request.addParameter("email", "User@Example.com");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertEquals(429, response.getStatus());
		assertEquals("900", response.getHeader("Retry-After"));
		verify(chain, never()).doFilter(any(), any());
	}
}
