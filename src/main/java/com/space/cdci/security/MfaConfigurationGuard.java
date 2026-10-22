package com.space.cdci.security;

import jakarta.annotation.PostConstruct;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MfaConfigurationGuard {
	private final boolean registrationEnabled;
	private final boolean mfaRequired;
	private final String mfaEncryptionKey;

	public MfaConfigurationGuard(@Value("${cdci.registration-enabled:false}") boolean registrationEnabled,
			@Value("${cdci.identity.mfa.required:false}") boolean mfaRequired,
			@Value("${cdci.identity.mfa.encryption-key:}") String mfaEncryptionKey) {
		this.registrationEnabled = registrationEnabled;
		this.mfaRequired = mfaRequired;
		this.mfaEncryptionKey = mfaEncryptionKey;
	}

	@PostConstruct
	void validate() {
		if (registrationEnabled && !mfaRequired) {
			throw new IllegalStateException("El registro habilitado requiere CDCI_MFA_REQUIRED=true.");
		}
		if (mfaRequired) {
			try {
				if (mfaEncryptionKey == null || Base64.getDecoder().decode(mfaEncryptionKey.trim()).length != 32) throw new IllegalArgumentException();
			} catch (IllegalArgumentException exception) {
				throw new IllegalStateException("CDCI_MFA_ENCRYPTION_KEY debe ser Base64 de 32 bytes cuando MFA es obligatorio.", exception);
			}
		}
	}
}
