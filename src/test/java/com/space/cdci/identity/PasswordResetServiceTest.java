package com.space.cdci.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.space.cdci.admin.SessionInvalidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
	@Mock private UserRepository users;
	@Mock private PasswordResetTokenRepository tokens;
	@Mock private PasswordEncoder passwordEncoder;
	@Mock private ApplicationEventPublisher events;
	@Mock private SessionInvalidator sessionInvalidator;
	@Mock private AccessAuditService audit;
	@Mock private AuthRequestRateLimiter rateLimiter;
	@Mock private HttpServletRequest httpRequest;
	private PasswordResetService service;

	@BeforeEach
	void setUp() {
		service = new PasswordResetService(users, tokens, passwordEncoder, events, sessionInvalidator, audit,
			rateLimiter, true, "http://localhost:3000", 20);
		when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.10");
		when(rateLimiter.allow(anyString(), anyString(), anyInt(), any(Duration.class))).thenReturn(true);
	}

	@Test
	void requestUsesSameResponseForUnknownEmailAndSendsNoEmail() {
		when(users.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

		String response = service.requestReset(new AuthDtos.PasswordResetRequest("Missing@Example.com"), httpRequest);

		assertEquals("Si la cuenta existe, recibirás instrucciones para restablecer la contraseña.", response);
		verify(events, never()).publishEvent(any(PasswordResetEmailRequested.class));
	}

	@Test
	void resetsPasswordAndInvalidatesExistingSessions() {
		UserAccount user = new UserAccount("person@example.com", "Test User", "old-hash", Set.of());
		when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
		service.requestReset(new AuthDtos.PasswordResetRequest(user.getEmail()), httpRequest);

		ArgumentCaptor<PasswordResetEmailRequested> eventCaptor = ArgumentCaptor.forClass(PasswordResetEmailRequested.class);
		verify(events).publishEvent(eventCaptor.capture());
		ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
		verify(tokens).save(tokenCaptor.capture());
		String resetUrl = eventCaptor.getValue().resetUrl();
		String token = resetUrl.substring(resetUrl.indexOf("token=") + 6);
		PasswordResetToken storedToken = tokenCaptor.getValue();
		assertEquals(64, storedToken.getTokenHash().length());
		assertNotNull(storedToken.getExpiresAt());
		when(tokens.findByTokenHashForUpdate(storedToken.getTokenHash())).thenReturn(Optional.of(storedToken));
		when(passwordEncoder.encode("a-long-new-password")).thenReturn("new-hash");

		service.resetPassword(new AuthDtos.PasswordResetConfirmRequest(token, "a-long-new-password"), httpRequest);

		assertEquals("new-hash", user.getPasswordHash());
		assertNotNull(storedToken.getConsumedAt());
		verify(sessionInvalidator).invalidate(user.getEmail());
		verify(audit).record(user.getEmail(), "USER_PASSWORD_RESET", "USER", user.getId().toString(),
			"Contraseña restablecida mediante enlace de un solo uso.");
	}

	@Test
	void rejectsExpiredResetTokens() {
		UserAccount user = new UserAccount("person@example.com", "Test User", "old-hash", Set.of());
		PasswordResetToken expired = new PasswordResetToken(user, "f".repeat(64), Instant.now().minusSeconds(1));
		when(tokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(expired));

		org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
				() -> service.resetPassword(new AuthDtos.PasswordResetConfirmRequest("expired-token", "a-long-new-password"), httpRequest));
		verify(sessionInvalidator, never()).invalidate(anyString());
	}
}
