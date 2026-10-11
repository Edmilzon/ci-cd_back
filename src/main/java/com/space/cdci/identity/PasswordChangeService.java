package com.space.cdci.identity;

import com.space.cdci.admin.SessionInvalidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordChangeService {
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final SessionInvalidator sessionInvalidator;
	private final AccessAuditService audit;

	public PasswordChangeService(UserRepository users, PasswordEncoder passwordEncoder, SessionInvalidator sessionInvalidator, AccessAuditService audit) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.sessionInvalidator = sessionInvalidator;
		this.audit = audit;
	}

	@Transactional
	public void change(CdciPrincipal principal, AuthDtos.PasswordChangeRequest request, HttpServletRequest httpRequest) {
		UserAccount user = users.findById(principal.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta.");
		}
		if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige una contraseña distinta a la actual.");
		}
		user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
		var currentSession = httpRequest.getSession(false);
		String activeSessionId = currentSession == null ? null : currentSession.getId();
		sessionInvalidator.invalidateExcept(user.getEmail(), activeSessionId);
		audit.record(user.getEmail(), "USER_PASSWORD_CHANGED", "USER", user.getId().toString(), "Contraseña actualizada; otras sesiones cerradas.");
	}
}
