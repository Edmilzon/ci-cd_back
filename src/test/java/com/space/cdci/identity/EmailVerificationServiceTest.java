package com.space.cdci.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
	@Mock private UserRepository users;
	@Mock private EmailVerificationTokenRepository tokens;
	@Mock private ApplicationEventPublisher events;
	@Mock private AuthRequestRateLimiter rateLimiter;
	@Mock private AccessAuditService audit;
	@Mock private HttpServletRequest httpRequest;
	private EmailVerificationService service;

	@BeforeEach
	void setUp() {
		service = new EmailVerificationService(users, tokens, events, rateLimiter, audit, true, true, "http://localhost:3000");
		when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.10");
		when(rateLimiter.allow(anyString(), anyString(), anyInt(), any(Duration.class))).thenReturn(true);
	}

	@Test
	void resendDoesNotRevealWhetherEmailHasAnAccount() {
		when(users.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

		String response = service.resend(new AuthDtos.EmailVerificationRequest("Missing@Example.com"), httpRequest);

		assertEquals("Si la cuenta está pendiente de verificación, recibirás un correo con los siguientes pasos.", response);
		verify(events, never()).publishEvent(any(EmailVerificationRequested.class));
	}

	@Test
	void confirmationVerifiesAccountAndConsumesItsToken() {
		UserAccount user = new UserAccount("person@example.com", "Test User", "hash", Set.of(), false);
		EmailVerificationToken token = new EmailVerificationToken(user, "f".repeat(64), Instant.now().plusSeconds(60));
		when(tokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));

		service.confirm(new AuthDtos.EmailVerificationConfirmRequest("opaque-token"), httpRequest);

		assertTrue(user.isEmailVerified());
		assertNotNull(token.getConsumedAt());
		verify(tokens).deleteAllForUser(user.getId());
		verify(audit).record(user.getEmail(), "USER_EMAIL_VERIFIED", "USER", user.getId().toString(), "Correo de cuenta verificado.");
	}

	@Test
	void rejectsExpiredVerificationTokens() {
		UserAccount user = new UserAccount("person@example.com", "Test User", "hash", Set.of(), false);
		EmailVerificationToken expired = new EmailVerificationToken(user, "f".repeat(64), Instant.now().minusSeconds(1));
		when(tokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(expired));

		org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
				() -> service.confirm(new AuthDtos.EmailVerificationConfirmRequest("expired-token"), httpRequest));
		assertFalse(user.isEmailVerified());
	}
}
