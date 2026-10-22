package com.space.cdci.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP using HMAC-SHA1, a 30-second time step, and six decimal digits. */
final class Totp {
	static final long STEP_SECONDS = 30;
	private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {}

	static String newSecret() {
		byte[] secret = new byte[20];
		RANDOM.nextBytes(secret);
		return encodeBase32(secret);
	}

	static OptionalLong matchingStep(String secret, String suppliedCode, Instant now, long lastAcceptedStep) {
		if (suppliedCode == null || !suppliedCode.matches("[0-9]{6}")) return OptionalLong.empty();
		long current = now.getEpochSecond() / STEP_SECONDS;
		long matching = -1;
		for (long candidate = Math.max(0, current - 1); candidate <= current + 1; candidate++) {
			if (candidate <= lastAcceptedStep) continue;
			if (MessageDigest.isEqual(code(secret, candidate).getBytes(StandardCharsets.US_ASCII), suppliedCode.getBytes(StandardCharsets.US_ASCII))) {
				matching = Math.max(matching, candidate);
			}
		}
		return matching < 0 ? OptionalLong.empty() : OptionalLong.of(matching);
	}

	static String codeAt(String secret, Instant instant) {
		return code(secret, instant.getEpochSecond() / STEP_SECONDS);
	}

	static String encodeBase32(byte[] bytes) {
		StringBuilder output = new StringBuilder((bytes.length * 8 + 4) / 5);
		int buffer = 0;
		int bitsLeft = 0;
		for (byte value : bytes) {
			buffer = (buffer << 8) | (value & 0xff);
			bitsLeft += 8;
			while (bitsLeft >= 5) {
				output.append(BASE32[(buffer >> (bitsLeft - 5)) & 31]);
				bitsLeft -= 5;
			}
		}
		if (bitsLeft > 0) output.append(BASE32[(buffer << (5 - bitsLeft)) & 31]);
		return output.toString();
	}

	private static byte[] decodeBase32(String value) {
		String normalized = value.replaceAll("[=\\s-]", "").toUpperCase(java.util.Locale.ROOT);
		byte[] output = new byte[normalized.length() * 5 / 8];
		int buffer = 0;
		int bitsLeft = 0;
		int offset = 0;
		for (char character : normalized.toCharArray()) {
			int digit = new String(BASE32).indexOf(character);
			if (digit < 0) throw new IllegalArgumentException("El secreto TOTP no es Base32 válido.");
			buffer = (buffer << 5) | digit;
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				output[offset++] = (byte) (buffer >> (bitsLeft - 8));
				bitsLeft -= 8;
			}
		}
		if (offset == 0) throw new IllegalArgumentException("El secreto TOTP está vacío.");
		return output;
	}

	private static String code(String secret, long counter) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
			byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
			int offset = digest[digest.length - 1] & 0x0f;
			int binary = ((digest[offset] & 0x7f) << 24)
					| ((digest[offset + 1] & 0xff) << 16)
					| ((digest[offset + 2] & 0xff) << 8)
					| (digest[offset + 3] & 0xff);
			return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("No se pudo validar el código TOTP.", exception);
		}
	}
}
