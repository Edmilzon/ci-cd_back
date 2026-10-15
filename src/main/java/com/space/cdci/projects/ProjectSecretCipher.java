package com.space.cdci.projects;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProjectSecretCipher {
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private final String configuredKey;
	private final SecureRandom random = new SecureRandom();

	public ProjectSecretCipher(@Value("${cdci.secret.encryption-key:}") String configuredKey) { this.configuredKey = configuredKey; }

	public String encrypt(UUID projectId, String name, String value) {
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			random.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(projectId, name));
			byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo proteger el secreto del proyecto.", exception);
		}
	}

	public String decrypt(UUID projectId, String name, String ciphertext) {
		try {
			byte[] bytes = Base64.getDecoder().decode(ciphertext);
			if (bytes.length <= NONCE_BYTES) throw new IllegalArgumentException("Ciphertext inválido.");
			byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, NONCE_BYTES);
			byte[] encrypted = java.util.Arrays.copyOfRange(bytes, NONCE_BYTES, bytes.length);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(projectId, name));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo leer el secreto cifrado del proyecto.", exception);
		}
	}

	private static byte[] aad(UUID projectId, String name) {
		return (projectId + ":" + name).getBytes(StandardCharsets.UTF_8);
	}

	private SecretKeySpec key() {
		if (configuredKey == null || configuredKey.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configura CDCI_SECRET_ENCRYPTION_KEY para habilitar secretos de pipeline.");
		}
		try {
			byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
			if (decoded.length != 32) throw new IllegalArgumentException();
			return new SecretKeySpec(decoded, "AES");
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("CDCI_SECRET_ENCRYPTION_KEY debe ser una clave base64 de 32 bytes.", exception);
		}
	}
}
