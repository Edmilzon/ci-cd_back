package com.space.cdci.identity;

import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityBootstrapService {
	private final RoleRepository roles;
	private final PermissionRepository permissions;
	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final String adminEmail;
	private final String adminPassword;

	public IdentityBootstrapService(RoleRepository roles, PermissionRepository permissions, UserRepository users, PasswordEncoder passwordEncoder,
			@Value("${cdci.bootstrap-admin-email:}") String adminEmail,
			@Value("${cdci.bootstrap-admin-password:}") String adminPassword) {
		this.roles = roles;
		this.permissions = permissions;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.adminEmail = adminEmail;
		this.adminPassword = adminPassword;
	}

	@Transactional
	public void seedIdentity() {
		Map<String, Permission> catalog = seedPermissions();
		Role viewer = findOrCreateRole("VIEWER", "Lector", "Puede consultar recursos autorizados.");
		Role admin = findOrCreateRole("ADMIN", "Administrador", "Administración completa de la plataforma.");
		Role maintainer = findOrCreateRole("MAINTAINER", "Mantenedor", "Administra pipelines y operaciones de despliegue.");
		Role developer = findOrCreateRole("DEVELOPER", "Desarrollador", "Ejecuta pipelines y consulta despliegues.");
		viewer.replacePermissions(select(catalog, "PIPELINE_READ", "DEPLOYMENT_READ"));
		developer.replacePermissions(select(catalog, "PIPELINE_READ", "PIPELINE_RUN", "DEPLOYMENT_READ"));
		maintainer.replacePermissions(select(catalog, "USER_READ", "ROLE_READ", "PIPELINE_READ", "PIPELINE_RUN", "PIPELINE_MANAGE", "DEPLOYMENT_READ", "DEPLOYMENT_RUN"));
		admin.replacePermissions(Set.copyOf(catalog.values()));
		roles.saveAll(Set.of(viewer, admin, maintainer, developer));
		if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) return;
		if (adminPassword.length() < 12 || adminPassword.length() > 128) {
			throw new IllegalStateException("CDCI_BOOTSTRAP_ADMIN_PASSWORD debe tener entre 12 y 128 caracteres.");
		}
		String normalizedEmail = adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
		if (users.findByEmailIgnoreCase(normalizedEmail).isPresent()) return;
		users.save(new UserAccount(normalizedEmail, "Administrador CDCI", passwordEncoder.encode(adminPassword), Set.of(admin)));
	}

	private Role findOrCreateRole(String code, String name, String description) {
		return roles.findByCode(code).orElseGet(() -> roles.save(new Role(code, name, description, true)));
	}

	private Map<String, Permission> seedPermissions() {
		Map<String, PermissionSpec> specifications = new LinkedHashMap<>();
		specifications.put("USER_READ", new PermissionSpec("Leer usuarios", "Consultar cuentas y su estado."));
		specifications.put("USER_MANAGE", new PermissionSpec("Administrar usuarios", "Activar o suspender cuentas."));
		specifications.put("USER_ROLE_ASSIGN", new PermissionSpec("Asignar roles", "Cambiar los roles de una cuenta."));
		specifications.put("ROLE_READ", new PermissionSpec("Leer roles", "Consultar roles y el catálogo de permisos."));
		specifications.put("ROLE_MANAGE", new PermissionSpec("Administrar roles", "Crear roles y definir sus permisos."));
		specifications.put("PIPELINE_READ", new PermissionSpec("Leer pipelines", "Consultar proyectos y ejecuciones."));
		specifications.put("PIPELINE_RUN", new PermissionSpec("Ejecutar pipelines", "Iniciar ejecuciones manuales."));
		specifications.put("PIPELINE_MANAGE", new PermissionSpec("Administrar pipelines", "Crear y modificar pipelines."));
		specifications.put("DEPLOYMENT_READ", new PermissionSpec("Leer despliegues", "Consultar entornos y despliegues."));
		specifications.put("DEPLOYMENT_RUN", new PermissionSpec("Ejecutar despliegues", "Promover versiones a entornos."));
		specifications.put("AUDIT_READ", new PermissionSpec("Leer auditoría", "Consultar cambios de acceso y administración."));
		specifications.put("SETTINGS_MANAGE", new PermissionSpec("Administrar configuración", "Modificar la configuración del espacio."));
		Map<String, Permission> result = new LinkedHashMap<>();
		specifications.forEach((code, spec) -> result.put(code, permissions.findByCode(code)
				.orElseGet(() -> permissions.save(new Permission(code, spec.name(), spec.description())))));
		return result;
	}

	private Set<Permission> select(Map<String, Permission> catalog, String... codes) {
		return java.util.Arrays.stream(codes).map(code -> {
			Permission permission = catalog.get(code);
			if (permission == null) throw new IllegalStateException("Permiso de sistema no encontrado: " + code);
			return permission;
		}).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private record PermissionSpec(String name, String description) {}
}
