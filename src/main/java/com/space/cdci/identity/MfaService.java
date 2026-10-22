package com.space.cdci.identity;

import com.space.cdci.admin.SessionInvalidator;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MfaService {
	public static final String PENDING_USER_ATTRIBUTE = "CDCI_MFA_PENDING_USER";
	public static final String PENDING_AT_ATTRIBUTE = "CDCI_MFA_PENDING_AT";
	public static final String PENDING_SETUP_ATTRIBUTE = "CDCI_MFA_PENDING_SETUP";
	public static final String AUTHENTICATED_AT_ATTRIBUTE = "CDCI_AUTHENTICATED_AT";
	static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
	private static final Duration ENROLLMENT_TTL = Duration.ofMinutes(10);
	private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
	private static final int RECOVERY_CODE_COUNT = 10;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final UserRepository users;
	private final AuthRequestRateLimiter rateLimiter;
	private final AccessAuditService audit;
	private final SessionInvalidator sessions;
	private final String configuredEncryptionKey;
	private final boolean requiredForLogin;

	public MfaService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, UserRepository users,
			AuthRequestRateLimiter rateLimiter, AccessAuditService audit, SessionInvalidator sessions,
			@Value("${cdci.identity.mfa.encryption-key:}") String configuredEncryptionKey,
			@Value("${cdci.identity.mfa.required:false}") boolean requiredForLogin) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.users = users;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
		this.sessions = sessions;
		this.configuredEncryptionKey = configuredEncryptionKey;
		this.requiredForLogin = requiredForLogin;
	}

	public boolean isRequiredForLogin() { return requiredForLogin; }
	public boolean isAvailable() {
		try { key(); return true; }
		catch (ResponseStatusException | IllegalStateException exception) { return false; }
	}

	@Transactional(readOnly = true)
	public boolean isEnabled(UUID userId) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_mfa_factors WHERE user_id = ? AND enabled = TRUE", Integer.class, userId);
		return count != null && count > 0;
	}

	@Transactional(readOnly = true)
	public AuthDtos.MfaStatusResponse status(UUID userId) {
		Factor factor = findFactor(userId);
		int remaining = factor == null || !factor.enabled() ? 0 : jdbc.queryForObject(
				"SELECT COUNT(*) FROM user_mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL", Integer.class, userId);
		return new AuthDtos.MfaStatusResponse(isAvailable(), factor != null && factor.enabled(), remaining);
	}

	@Transactional
	public AuthDtos.MfaSetupResponse startEnrollment(UUID userId, String currentPassword) {
		ensureAvailable();
		UserAccount user = users.findById(userId).orElseThrow(() -> unauthorized("No se pudo validar la cuenta."));
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta.");
		}
		Factor current = findFactor(userId);
		if (current != null && current.enabled()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La autenticación MFA ya está activada.");
		}

		String secret = Totp.newSecret();
		Instant expiresAt = Instant.now().plus(ENROLLMENT_TTL);
		jdbc.update("""
			INSERT INTO user_mfa_factors(user_id, secret_ciphertext, enabled, enrollment_expires_at, last_accepted_step, created_at, updated_at)
			VALUES (?, ?, FALSE, ?, -1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			ON CONFLICT (user_id) DO UPDATE SET secret_ciphertext = EXCLUDED.secret_ciphertext,
			  enabled = FALSE, enrollment_expires_at = EXCLUDED.enrollment_expires_at,
			  last_accepted_step = -1, updated_at = CURRENT_TIMESTAMP
			""", userId, encrypt(userId, secret), Timestamp.from(expiresAt));
		jdbc.update("DELETE FROM user_mfa_recovery_codes WHERE user_id = ?", userId);
		String label = java.net.URLEncoder.encode("CDCI:" + user.getEmail(), StandardCharsets.UTF_8);
		String issuer = java.net.URLEncoder.encode("CDCI", StandardCharsets.UTF_8);
		String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=" + Totp.STEP_SECONDS;
		return new AuthDtos.MfaSetupResponse(secret, uri, expiresAt);
	}

	@Transactional
	public List<String> confirmEnrollment(UUID userId, String code) {
		Factor factor = findFactor(userId);
		if (factor == null || factor.enabled() || factor.enrollmentExpiresAt() == null || !factor.enrollmentExpiresAt().isAfter(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.GONE, "La configuración MFA venció. Iníciala nuevamente.");
		}
		UserAccount user = users.findById(userId).orElseThrow(() -> unauthorized("No se pudo validar la cuenta."));
		limit("mfa-enrollment-user", user.getEmail(), 10);
		String secret = decrypt(userId, factor.secretCiphertext());
		var matching = Totp.matchingStep(secret, code, Instant.now(), factor.lastAcceptedStep());
		if (matching.isEmpty()) throw invalidCode();
		int updated = jdbc.update("""
			UPDATE user_mfa_factors SET enabled = TRUE, enrollment_expires_at = NULL,
			  last_accepted_step = ?, updated_at = CURRENT_TIMESTAMP
			WHERE user_id = ? AND enabled = FALSE AND enrollment_expires_at > CURRENT_TIMESTAMP
			""", matching.getAsLong(), userId);
		if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "La configuración MFA cambió. Inténtalo nuevamente.");
		List<String> codes = replaceRecoveryCodes(userId);
		audit.record(user.getEmail(), "USER_MFA_ENABLED", "USER", userId.toString(), "Autenticación multifactor TOTP activada.");
		return codes;
	}

	@Transactional
	public UUID verifyLogin(String code, String remoteAddress, UUID userId) {
		limit("mfa-login-ip", remoteAddress == null ? "unknown" : remoteAddress, 20);
		UserAccount user = users.findById(userId).orElseThrow(() -> unauthorized("El desafío MFA venció. Inicia sesión nuevamente."));
		limit("mfa-login-user", user.getEmail(), 10);
		Factor factor = findFactor(userId);
		if (factor == null || !factor.enabled()) throw invalidCode();
		ProofKind proof = consumeProof(userId, factor, code);
		if (proof == null) throw invalidCode();
		audit.record(user.getEmail(), proof == ProofKind.RECOVERY ? "USER_MFA_RECOVERY_CODE_USED" : "USER_MFA_LOGIN",
				"USER", userId.toString(), proof == ProofKind.RECOVERY ? "Inicio de sesión completado con un código de recuperación." : "Inicio de sesión completado con MFA.");
		return userId;
	}

	@Transactional
	public List<String> regenerateRecoveryCodes(CdciPrincipal principal, AuthDtos.MfaRecoveryCodesRequest request) {
		UserAccount user = users.findById(principal.id()).orElseThrow(() -> unauthorized("No se pudo validar la cuenta."));
		requirePassword(user, request.currentPassword());
		limit("mfa-management-user", user.getEmail(), 10);
		Factor factor = findFactor(user.getId());
		if (factor == null || !factor.enabled() || consumeProof(user.getId(), factor, request.code()) == null) throw invalidCode();
		List<String> codes = replaceRecoveryCodes(user.getId());
		audit.record(user.getEmail(), "USER_MFA_RECOVERY_CODES_ROTATED", "USER", user.getId().toString(), "Códigos de recuperación MFA regenerados.");
		return codes;
	}

	@Transactional
	public void disable(CdciPrincipal principal, AuthDtos.MfaDisableRequest request, HttpServletRequest httpRequest) {
		UserAccount user = users.findById(principal.id()).orElseThrow(() -> unauthorized("No se pudo validar la cuenta."));
		requirePassword(user, request.currentPassword());
		limit("mfa-management-user", user.getEmail(), 10);
		Factor factor = findFactor(user.getId());
		if (factor == null || !factor.enabled() || consumeProof(user.getId(), factor, request.code()) == null) throw invalidCode();
		jdbc.update("DELETE FROM user_mfa_factors WHERE user_id = ?", user.getId());
		var currentSession = httpRequest.getSession(false);
		sessions.invalidateExcept(user.getEmail(), currentSession == null ? null : currentSession.getId());
		audit.record(user.getEmail(), "USER_MFA_DISABLED", "USER", user.getId().toString(), "Autenticación multifactor TOTP desactivada; otras sesiones cerradas.");
	}

	@Transactional(readOnly = true)
	public PendingChallenge pendingChallenge(jakarta.servlet.http.HttpSession session) {
		if (session == null) return null;
		Object rawUser = session.getAttribute(PENDING_USER_ATTRIBUTE);
		Object rawAt = session.getAttribute(PENDING_AT_ATTRIBUTE);
		Object rawSetup = session.getAttribute(PENDING_SETUP_ATTRIBUTE);
		if (!(rawUser instanceof String userId) || !(rawAt instanceof Long createdAt)) return null;
		try {
			UUID id = UUID.fromString(userId);
			boolean setupRequired = Boolean.TRUE.equals(rawSetup);
			Duration ttl = setupRequired ? ENROLLMENT_TTL : CHALLENGE_TTL;
			if (Instant.ofEpochMilli(createdAt).plus(ttl).isBefore(Instant.now())) return null;
			return new PendingChallenge(id, setupRequired);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	@Transactional(readOnly = true)
	public CdciPrincipal principal(UUID userId) {
		UserAccount user = users.findWithAccessById(userId).orElseThrow(() -> unauthorized("El desafío MFA venció. Inicia sesión nuevamente."));
		return CdciPrincipal.from(user);
	}

	private Factor findFactor(UUID userId) {
		try {
			return jdbc.queryForObject("SELECT secret_ciphertext, enabled, enrollment_expires_at, last_accepted_step FROM user_mfa_factors WHERE user_id = ?",
					(rs, row) -> new Factor(rs.getString("secret_ciphertext"), rs.getBoolean("enabled"),
							rs.getTimestamp("enrollment_expires_at") == null ? null : rs.getTimestamp("enrollment_expires_at").toInstant(),
							rs.getLong("last_accepted_step")), userId);
		} catch (EmptyResultDataAccessException exception) {
			return null;
		}
	}

	private ProofKind consumeProof(UUID userId, Factor factor, String supplied) {
		String secret = decrypt(userId, factor.secretCiphertext());
		var matching = Totp.matchingStep(secret, supplied, Instant.now(), factor.lastAcceptedStep());
		if (matching.isPresent()) {
			int updated = jdbc.update("UPDATE user_mfa_factors SET last_accepted_step = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND enabled = TRUE AND last_accepted_step < ?",
					matching.getAsLong(), userId, matching.getAsLong());
			if (updated == 1) return ProofKind.TOTP;
		}
		String normalized = normalizeRecoveryCode(supplied);
		if (normalized == null) return null;
		String hash = digest(normalized);
		return jdbc.update("UPDATE user_mfa_recovery_codes SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND code_hash = ? AND used_at IS NULL", userId, hash) == 1
				? ProofKind.RECOVERY : null;
	}

	private List<String> replaceRecoveryCodes(UUID userId) {
		jdbc.update("DELETE FROM user_mfa_recovery_codes WHERE user_id = ?", userId);
		List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
		for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
			byte[] bytes = new byte[16];
			RANDOM.nextBytes(bytes);
			String raw = HexFormat.of().withUpperCase().formatHex(bytes);
			String formatted = raw.replaceAll("(.{4})(?=.)", "$1-");
			codes.add(formatted);
			jdbc.update("INSERT INTO user_mfa_recovery_codes(id, user_id, code_hash) VALUES (?, ?, ?)", UUID.randomUUID(), userId, digest(raw));
		}
		return List.copyOf(codes);
	}

	private void requirePassword(UserAccount user, String password) {
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta.");
		}
	}

	private void limit(String scope, String subject, int maximum) {
		if (!rateLimiter.allow(scope, subject, maximum, RATE_LIMIT_WINDOW)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Espera antes de volver a probar.");
		}
	}

	private void ensureAvailable() {
		if (configuredEncryptionKey == null || configuredEncryptionKey.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configura CDCI_MFA_ENCRYPTION_KEY para habilitar MFA.");
		}
		key();
	}

	private SecretKeySpec key() {
		if (configuredEncryptionKey == null || configuredEncryptionKey.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configura CDCI_MFA_ENCRYPTION_KEY para habilitar MFA.");
		}
		try {
			byte[] decoded = Base64.getDecoder().decode(configuredEncryptionKey.trim());
			if (decoded.length != 32) throw new IllegalArgumentException();
			return new SecretKeySpec(decoded, "AES");
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("CDCI_MFA_ENCRYPTION_KEY debe ser una clave Base64 de 32 bytes.", exception);
		}
	}

	private String encrypt(UUID userId, String secret) {
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			RANDOM.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo proteger el secreto TOTP.", exception);
		}
	}

	private String decrypt(UUID userId, String ciphertext) {
		try {
			byte[] bytes = Base64.getDecoder().decode(ciphertext);
			if (bytes.length <= NONCE_BYTES) throw new IllegalArgumentException("Ciphertext inválido.");
			byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, NONCE_BYTES);
			byte[] encrypted = java.util.Arrays.copyOfRange(bytes, NONCE_BYTES, bytes.length);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo leer el secreto TOTP protegido.", exception);
		}
	}

	private static String normalizeRecoveryCode(String value) {
		if (value == null) return null;
		String normalized = value.replaceAll("[-\\s]", "").toUpperCase(java.util.Locale.ROOT);
		return normalized.matches("[0-9A-F]{32}") ? normalized : null;
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("No se pudo resumir el código de recuperación.", exception);
		}
	}

	private static ResponseStatusException unauthorized(String message) {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
	}

	private static ResponseStatusException invalidCode() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "El código MFA no es válido o ya fue utilizado.");
	}

	@Scheduled(fixedDelayString = "${cdci.identity.mfa.cleanup-interval-ms:3600000}")
	@Transactional
	public void purgeExpiredEnrollments() {
		jdbc.update("DELETE FROM user_mfa_factors WHERE enabled = FALSE AND enrollment_expires_at <= CURRENT_TIMESTAMP");
	}

	public record PendingChallenge(UUID userId, boolean setupRequired) {}
	private enum ProofKind { TOTP, RECOVERY }
	private record Factor(String secretCiphertext, boolean enabled, Instant enrollmentExpiresAt, long lastAcceptedStep) {}
}
