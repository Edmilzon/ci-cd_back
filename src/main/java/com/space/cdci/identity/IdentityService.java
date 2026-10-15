package com.space.cdci.identity;

import java.util.Locale;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
	private final UserRepository users;
	private final RoleRepository roles;
	private final PasswordEncoder passwordEncoder;
	private final boolean registrationEnabled;
	private final EmailVerificationService emailVerification;

	public IdentityService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
			EmailVerificationService emailVerification, @Value("${cdci.registration-enabled:true}") boolean registrationEnabled) {
		this.users = users;
		this.roles = roles;
		this.passwordEncoder = passwordEncoder;
		this.registrationEnabled = registrationEnabled;
		this.emailVerification = emailVerification;
		emailVerification.validateConfiguration(registrationEnabled);
	}

	public AuthDtos.RegistrationStatusResponse registrationStatus() {
		return new AuthDtos.RegistrationStatusResponse(registrationEnabled);
	}

	@Transactional
	public AuthDtos.RegisterResponse register(AuthDtos.RegisterRequest request, HttpServletRequest httpRequest) {
		if (!registrationEnabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El registro está deshabilitado.");
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		emailVerification.guardRegistration(email, httpRequest);
		if (users.findByEmailIgnoreCase(email).isPresent()) {
			if (emailVerification.isRequired()) return new AuthDtos.RegisterResponse(true, false);
			throw new EmailAlreadyRegisteredException();
		}
		Role viewer = roles.findByCode("VIEWER").orElseThrow(() -> new IllegalStateException("No existe el rol base VIEWER."));
		UserAccount created = new UserAccount(email, request.fullName().trim(), passwordEncoder.encode(request.password()), Set.of(viewer), !emailVerification.isRequired());
		try {
			UserAccount saved = users.saveAndFlush(created);
			emailVerification.issueForNewAccount(saved);
			return new AuthDtos.RegisterResponse(emailVerification.isRequired(), saved.isEmailVerified());
		} catch (DataIntegrityViolationException exception) {
			throw new EmailAlreadyRegisteredException();
		}
	}
}
