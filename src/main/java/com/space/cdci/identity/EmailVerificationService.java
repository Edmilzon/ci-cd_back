package com.space.cdci.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailVerificationService {
	private static final String GENERIC_MESSAGE = "Si la cuenta está pendiente de verificación, recibirás un correo con los siguientes pasos.";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final UserRepository users;
	private final EmailVerificationTokenRepository tokens;
	private final ApplicationEventPublisher events;
	private final AuthRequestRateLimiter rateLimiter;
	private final AccessAuditService audit;
	private final boolean required;
	private final boolean emailEnabled;
	private final String frontendOrigin;

	public EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens,
			ApplicationEventPublisher events, AuthRequestRateLimiter rateLimiter, AccessAuditService audit,
			@Value("${cdci.identity.email-verification-required:false}") boolean required,
			@Value("${cdci.email.enabled:false}") boolean emailEnabled,
			@Value("${cdci.frontend-origin}") String frontendOrigin) {
		this.users = users;
		this.tokens = tokens;
		this.events = events;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
		this.required = required;
		this.emailEnabled = emailEnabled;
		this.frontendOrigin = frontendOrigin.replaceAll("/+$", "");
	}

	public boolean isRequired() { return required; }

	public void validateConfiguration(boolean registrationEnabled) {
		if (registrationEnabled && required && !emailEnabled) {
			throw new IllegalStateException("CDCI_EMAIL_ENABLED=true es obligatorio cuando la verificación de correo y el registro están habilitados.");
		}
	}

	public void guardRegistration(String email, HttpServletRequest httpRequest) {
		String remoteAddress = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
		boolean ipAllowed = rateLimiter.allow("registration-ip", remoteAddress, 30, Duration.ofMinutes(15));
		boolean emailAllowed = rateLimiter.allow("registration-email", email, 5, Duration.ofHours(1));
		if (!ipAllowed || !emailAllowed) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Se alcanzó el límite de solicitudes. Inténtalo más tarde.");
		}
	}

	@Transactional
	public void issueForNewAccount(UserAccount user) {
		if (!required || !emailEnabled) return;
		issue(user);
	}

	@Transactional
	public String resend(AuthDtos.EmailVerificationRequest request, HttpServletRequest httpRequest) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		String remoteAddress = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
		boolean ipAllowed = rateLimiter.allow("email-verification-ip", remoteAddress, 20, Duration.ofMinutes(15));
		boolean emailAllowed = rateLimiter.allow("email-verification-email", email, 3, Duration.ofMinutes(15));
		if (!ipAllowed || !emailAllowed || !required || !emailEnabled) return GENERIC_MESSAGE;
		users.findByEmailIgnoreCase(email).filter(UserAccount::isEnabled).filter(user -> !user.isEmailVerified()).ifPresent(this::issue);
		return GENERIC_MESSAGE;
	}

	@Transactional
	public void confirm(AuthDtos.EmailVerificationConfirmRequest request, HttpServletRequest httpRequest) {
		String remoteAddress = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
		if (!rateLimiter.allow("email-verification-confirm-ip", remoteAddress, 30, Duration.ofMinutes(15))) throw invalidToken();
		EmailVerificationToken token = tokens.findByTokenHashForUpdate(sha256(request.token())).orElseThrow(this::invalidToken);
		Instant now = Instant.now();
		if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)) throw invalidToken();
		UserAccount user = token.getUser();
		user.verifyEmail();
		token.consume(now);
		tokens.deleteAllForUser(user.getId());
		audit.record(user.getEmail(), "USER_EMAIL_VERIFIED", "USER", user.getId().toString(), "Correo de cuenta verificado.");
	}

	@Scheduled(fixedDelayString = "${cdci.identity.email-verification-cleanup-ms:3600000}")
	@Transactional
	public void purgeExpiredTokens() {
		tokens.purgeBefore(Instant.now().minus(Duration.ofDays(7)));
	}

	private void issue(UserAccount user) {
		tokens.deleteAllForUser(user.getId());
		String rawToken = newToken();
		tokens.save(new EmailVerificationToken(user, sha256(rawToken), Instant.now().plus(Duration.ofHours(24))));
		String url = frontendOrigin + "/verify-email?token=" + java.net.URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
		events.publishEvent(new EmailVerificationRequested(user.getEmail(), url));
	}

	private String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 no está disponible.", exception);
		}
	}

	private ResponseStatusException invalidToken() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, "El enlace no es válido o ya venció. Solicita uno nuevo.");
	}
}
