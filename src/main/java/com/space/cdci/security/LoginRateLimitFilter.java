package com.space.cdci.security;

import com.space.cdci.identity.AuthRequestRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class LoginRateLimitFilter extends OncePerRequestFilter {
	private final AuthRequestRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;

	public LoginRateLimitFilter(AuthRequestRateLimiter rateLimiter, ObjectMapper objectMapper) {
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equalsIgnoreCase(request.getMethod()) || !"/api/auth/login".equals(request.getServletPath());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String email = request.getParameter("email");
		String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		String remoteAddress = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
		boolean ipAllowed = rateLimiter.allow("login-ip", remoteAddress, 60, Duration.ofMinutes(15));
		boolean emailAllowed = rateLimiter.allow("login-email", normalizedEmail, 10, Duration.ofMinutes(15));
		if (!ipAllowed || !emailAllowed) {
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader("Retry-After", "900");
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			objectMapper.writeValue(response.getOutputStream(), Map.of("code", "RATE_LIMITED", "message", "Demasiados intentos. Espera unos minutos e inténtalo de nuevo."));
			return;
		}
		filterChain.doFilter(request, response);
	}
}
