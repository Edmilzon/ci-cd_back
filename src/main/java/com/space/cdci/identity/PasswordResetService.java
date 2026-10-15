package com.space.cdci.identity;

import com.space.cdci.admin.SessionInvalidator;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordResetService {
	private static final String GENERIC_MESSAGE = "Si la cuenta existe, recibirás instrucciones para restablecer la contraseña.";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final UserRepository users;
	private final PasswordResetTokenRepository tokens;
	private final PasswordEncoder passwordEncoder;
	private final ApplicationEventPublisher events;
	private final SessionInvalidator sessionInvalidator;
	private final AccessAuditService audit;
	private final AuthRequestRateLimiter rateLimiter;
	private final boolean emailEnabled;
	private final String frontendOrigin;
	private final Duration tokenLifetime;

	public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, PasswordEncoder passwordEncoder,
			ApplicationEventPublisher events, SessionInvalidator sessionInvalidator, AccessAuditService audit,
			AuthRequestRateLimiter rateLimiter, @Value("${cdci.email.enabled:false}") boolean emailEnabled,
			@Value("${cdci.frontend-origin}") String frontendOrigin,
			@Value("${cdci.identity.password-reset-ttl-minutes:20}") long lifetimeMinutes) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.events = events;
		this.sessionInvalidator = sessionInvalidator;
		this.audit = audit;
		this.rateLimiter = rateLimiter;
		this.emailEnabled = emailEnabled;
		this.frontendOrigin = frontendOrigin.replaceAll("/+$", "");
		this.tokenLifetime = Duration.ofMinutes(Math.max(5, Math.min(lifetimeMinutes, 60)));
	}

	@Transactional
	public String requestReset(AuthDtos.PasswordResetRequest request, HttpServletRequest httpRequest) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		String remoteAddress = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
		boolean ipAllowed = rateLimiter.allow("password-reset-ip", remoteAddress, 20, Duration.ofMinutes(15));
		boolean emailAllowed = rateLimiter.allow("password-reset-email", email, 3, Duration.ofMinutes(15));
		if (!ipAllowed || !emailAllowed || !emailEnabled) return GENERIC_MESSAGE;

		var user = users.findByEmailIgnoreCase(email).filter(UserAccount::isEnabled);
		if (user.isEmpty()) return GENERIC_MESSAGE;

		UserAccount account = user.get();
		tokens.deleteAllForUser(account.getId());
		String rawToken = newToken();
		Instant expiresAt = Instant.now().plus(tokenLifetime);
		tokens.save(new PasswordResetToken(account, sha256(rawToken), expiresAt));
		String resetUrl = frontendOrigin + "/reset-password?token=" + java.net.URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
		events.publishEvent(new PasswordResetEmailRequested(account.getEmail(), resetUrl, tokenLifetime.toMinutes()));
		return GENERIC_MESSAGE;
	}

	public boolean isEnabled() { return emailEnabled; }

	@Transactional
	public void resetPassword(AuthDtos.PasswordResetConfirmRequest request, HttpServletRequest httpRequest) {
		String remoteAddress = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
		if (!rateLimiter.allow("password-reset-confirm-ip", remoteAddress, 30, Duration.ofMinutes(15))) {
			throw invalidToken();
		}
		String hash = sha256(request.token());
		PasswordResetToken token = tokens.findByTokenHashForUpdate(hash).orElseThrow(this::invalidToken);
		Instant now = Instant.now();
		if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)) throw invalidToken();
		UserAccount user = token.getUser();
		if (!user.isEnabled()) throw invalidToken();
		user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
		token.consume(now);
		tokens.deleteOtherTokensForUser(user.getId(), token.getId());
		sessionInvalidator.invalidate(user.getEmail());
		audit.record(user.getEmail(), "USER_PASSWORD_RESET", "USER", user.getId().toString(), "Contraseña restablecida mediante enlace de un solo uso.");
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
