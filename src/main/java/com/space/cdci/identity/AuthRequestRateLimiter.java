package com.space.cdci.identity;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Component
public class AuthRequestRateLimiter {
	private static final String UPSERT = """
		INSERT INTO auth_rate_limit_buckets (scope, subject_hash, window_started_at, request_count, updated_at)
		VALUES (?, ?, ?, 1, ?)
		ON CONFLICT (scope, subject_hash) DO UPDATE SET
		  window_started_at = CASE WHEN auth_rate_limit_buckets.window_started_at <= ? THEN ? ELSE auth_rate_limit_buckets.window_started_at END,
		  request_count = CASE WHEN auth_rate_limit_buckets.window_started_at <= ? THEN 1 ELSE auth_rate_limit_buckets.request_count + 1 END,
		  updated_at = ?
		RETURNING request_count
		""";

	private final JdbcTemplate jdbc;
	private final byte[] hmacKey;

	public AuthRequestRateLimiter(JdbcTemplate jdbc, @Value("${cdci.identity.rate-limit-hmac-key}") String hmacKey) {
		this.jdbc = jdbc;
		this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
		if (this.hmacKey.length < 32) throw new IllegalStateException("CDCI_IDENTITY_RATE_LIMIT_HMAC_KEY debe tener al menos 32 bytes.");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean allow(String scope, String subject, int maximum, Duration window) {
		Instant now = Instant.now();
		Timestamp current = Timestamp.from(now);
		Timestamp cutoff = Timestamp.from(now.minus(window));
		Integer count = jdbc.queryForObject(UPSERT, Integer.class, scope, digest(scope + "\u0000" + subject), current, current,
				cutoff, current, cutoff, current);
		return count != null && count <= maximum;
	}

	@Scheduled(fixedDelayString = "${cdci.identity.rate-limit-cleanup-ms:3600000}")
	@Transactional
	public void purgeExpired() {
		jdbc.update("DELETE FROM auth_rate_limit_buckets WHERE updated_at < ?", Timestamp.from(Instant.now().minus(Duration.ofDays(2))));
	}

	private String digest(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
			return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("No se pudo proteger el identificador de rate limit.", exception);
		}
	}
}
