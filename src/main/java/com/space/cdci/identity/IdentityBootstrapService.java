package com.space.cdci.identity;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityBootstrapService {
	private final RoleRepository roles;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final String adminEmail;
	private final String adminPassword;

	public IdentityBootstrapService(RoleRepository roles, UserRepository users, PasswordEncoder passwordEncoder,
			@Value("${cdci.bootstrap-admin-email:}") String adminEmail,
			@Value("${cdci.bootstrap-admin-password:}") String adminPassword) {
		this.roles = roles;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.adminEmail = adminEmail;
		this.adminPassword = adminPassword;
	}

	@Transactional
	public void seedIdentity() {
		roles.findByCode("VIEWER").orElseGet(() -> roles.save(new Role("VIEWER", "Lector", "Puede consultar recursos autorizados.", true)));
		roles.findByCode("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Administrador", "Administración completa de la plataforma.", true)));
		if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) return;
		if (adminPassword.length() < 12 || adminPassword.length() > 128) {
			throw new IllegalStateException("CDCI_BOOTSTRAP_ADMIN_PASSWORD debe tener entre 12 y 128 caracteres.");
		}
		String normalizedEmail = adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
		if (users.findByEmailIgnoreCase(normalizedEmail).isPresent()) return;
		Role admin = roles.findByCode("ADMIN").orElseThrow();
		users.save(new UserAccount(normalizedEmail, "Administrador CDCI", passwordEncoder.encode(adminPassword), Set.of(admin)));
	}
}
