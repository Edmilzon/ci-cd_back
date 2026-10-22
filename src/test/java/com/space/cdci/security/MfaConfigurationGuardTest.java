package com.space.cdci.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MfaConfigurationGuardTest {
	private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	@Test
	void closedInternalRegistrationDoesNotRequireMfaConfiguration() {
		assertDoesNotThrow(() -> new MfaConfigurationGuard(false, false, "").validate());
	}

	@Test
	void openingRegistrationRequiresMfaAndEncryptionKey() {
		assertThrows(IllegalStateException.class, () -> new MfaConfigurationGuard(true, false, KEY).validate());
		assertThrows(IllegalStateException.class, () -> new MfaConfigurationGuard(true, true, "").validate());
		assertDoesNotThrow(() -> new MfaConfigurationGuard(true, true, KEY).validate());
	}
}
