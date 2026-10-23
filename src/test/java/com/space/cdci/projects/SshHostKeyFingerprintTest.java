package com.space.cdci.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SshHostKeyFingerprintTest {
	private static final String ABC_SHA256 = "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0";

	@Test
	void formatsSha256FingerprintsLikeOpenSsh() {
		assertEquals(ABC_SHA256, SshHostKeyFingerprint.fingerprint("abc".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void acceptsOnlyThePinnedHostKey() {
		var pin = new SshHostKeyFingerprint(ABC_SHA256);
		assertEquals(SshHostKeyFingerprint.OK, pin.check("server.example", "abc".getBytes(StandardCharsets.UTF_8)));
		assertEquals(SshHostKeyFingerprint.CHANGED, pin.check("server.example", "different".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void rejectsMalformedOrMissingPins() {
		assertThrows(IllegalArgumentException.class, () -> SshHostKeyFingerprint.normalize(null));
		assertThrows(IllegalArgumentException.class, () -> SshHostKeyFingerprint.normalize("SHA256:insecure"));
	}
}
