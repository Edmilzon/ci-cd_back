package com.space.cdci.projects;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** Pins each SSH connection to a SHA-256 host-key fingerprint verified by the project owner. */
public final class SshHostKeyFingerprint implements HostKeyRepository {
	private static final Pattern FORMAT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
	private final byte[] expectedDigest;
	private final String normalized;

	public SshHostKeyFingerprint(String fingerprint) {
		this.normalized = normalize(fingerprint);
		this.expectedDigest = Base64.getDecoder().decode(normalized.substring("SHA256:".length()));
	}

	public static String normalize(String fingerprint) {
		String value = fingerprint == null ? "" : fingerprint.trim();
		if (!FORMAT.matcher(value).matches()) {
			throw new IllegalArgumentException("La huella debe usar el formato OpenSSH SHA256:<base64>.");
		}
		return value;
	}

	public static String fingerprint(byte[] publicKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
			return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("No se pudo calcular la huella de la clave SSH.", exception);
		}
	}

	@Override
	public int check(String host, byte[] key) {
		byte[] actualDigest;
		try {
			actualDigest = MessageDigest.getInstance("SHA-256").digest(key);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("No se pudo verificar la huella de la clave SSH.", exception);
		}
		return MessageDigest.isEqual(expectedDigest, actualDigest) ? OK : CHANGED;
	}

	@Override public void add(HostKey hostkey, UserInfo ui) { /* Pins are changed only through the authenticated project API. */ }
	@Override public void remove(String host, String type) { /* Pins are immutable during an SSH session. */ }
	@Override public void remove(String host, String type, byte[] key) { /* Pins are immutable during an SSH session. */ }
	@Override public String getKnownHostsRepositoryID() { return "CDCI pinned host key: " + normalized; }
	@Override public HostKey[] getHostKey() { return new HostKey[0]; }
	@Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
}
