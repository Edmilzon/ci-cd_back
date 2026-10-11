package com.space.cdci.identity;

import java.util.Locale;
import java.util.Set;
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

	public IdentityService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
			@Value("${cdci.registration-enabled:true}") boolean registrationEnabled) {
		this.users = users;
		this.roles = roles;
		this.passwordEncoder = passwordEncoder;
		this.registrationEnabled = registrationEnabled;
	}

	@Transactional
	public AuthDtos.UserResponse register(AuthDtos.RegisterRequest request) {
		if (!registrationEnabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El registro está deshabilitado.");
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		if (users.findByEmailIgnoreCase(email).isPresent()) throw new EmailAlreadyRegisteredException();
		Role viewer = roles.findByCode("VIEWER").orElseThrow(() -> new IllegalStateException("No existe el rol base VIEWER."));
		UserAccount created = new UserAccount(email, request.fullName().trim(), passwordEncoder.encode(request.password()), Set.of(viewer));
		try {
			return AuthDtos.UserResponse.from(users.saveAndFlush(created));
		} catch (DataIntegrityViolationException exception) {
			throw new EmailAlreadyRegisteredException();
		}
	}
}
