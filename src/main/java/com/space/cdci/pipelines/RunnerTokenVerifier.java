package com.space.cdci.pipelines;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RunnerTokenVerifier {
	private final String configuredToken;

	public RunnerTokenVerifier(@Value("${cdci.runner.token:}") String configuredToken) { this.configuredToken = configuredToken; }

	public void require(String authorization) {
		if (configuredToken == null || configuredToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El acceso de workers no está configurado.");
		}
		String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
		if (!MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales de worker no válidas.");
		}
	}
}
