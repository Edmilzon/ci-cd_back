package com.space.cdci.security;

import com.space.cdci.identity.MfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AbsoluteSessionExpirationFilter extends OncePerRequestFilter {
	private final Duration lifetime;

	public AbsoluteSessionExpirationFilter(Duration lifetime) {
		if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) throw new IllegalArgumentException("La vigencia absoluta de sesión debe ser positiva.");
		this.lifetime = lifetime;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				Object rawAuthenticatedAt = session.getAttribute(MfaService.AUTHENTICATED_AT_ATTRIBUTE);
				long authenticatedAt = rawAuthenticatedAt instanceof Number number ? number.longValue() : Instant.now().toEpochMilli();
				if (!(rawAuthenticatedAt instanceof Number)) session.setAttribute(MfaService.AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
				if (Instant.now().toEpochMilli() - authenticatedAt >= lifetime.toMillis()) {
					SecurityContextHolder.clearContext();
					request.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
					session.invalidate();
				}
			}
		}
		filterChain.doFilter(request, response);
	}
}
