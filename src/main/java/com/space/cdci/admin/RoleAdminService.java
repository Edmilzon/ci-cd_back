package com.space.cdci.admin;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.Permission;
import com.space.cdci.identity.PermissionRepository;
import com.space.cdci.identity.Role;
import com.space.cdci.identity.RoleRepository;
import com.space.cdci.identity.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleAdminService {
	private final RoleRepository roles;
	private final PermissionRepository permissions;
	private final UserRepository users;
	private final SessionInvalidator sessionInvalidator;
	private final AccessAuditService audit;

	public RoleAdminService(RoleRepository roles, PermissionRepository permissions, UserRepository users,
			SessionInvalidator sessionInvalidator, AccessAuditService audit) {
		this.roles = roles;
		this.permissions = permissions;
		this.users = users;
		this.sessionInvalidator = sessionInvalidator;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<AdminDtos.RoleSummary> listRoles() {
		return roles.findAllWithPermissions().stream().map(role -> AdminDtos.RoleSummary.from(role, users.countByRoles_Code(role.getCode()))).toList();
	}

	@Transactional(readOnly = true)
	public List<AdminDtos.PermissionSummary> listPermissions() {
		return permissions.findAllByOrderByCodeAsc().stream().map(AdminDtos.PermissionSummary::from).toList();
	}

	@Transactional
	public AdminDtos.RoleSummary create(AdminDtos.CreateRoleRequest request, String actor) {
		String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
		if (roles.existsByCode(code)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un rol con ese código.");
		Role created = new Role(code, request.name().trim(), request.description().trim(), false);
		try {
			created = roles.saveAndFlush(created);
		} catch (DataIntegrityViolationException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un rol con ese código.");
		}
		audit.record(actor, "ROLE_CREATED", "ROLE", code, "Rol personalizado creado.");
		return AdminDtos.RoleSummary.from(created, 0);
	}

	@Transactional
	public AdminDtos.RoleSummary update(String code, AdminDtos.UpdateRoleRequest request, String actor) {
		Role role = managedRole(code);
		ensureCustom(role);
		role.update(request.name().trim(), request.description().trim());
		audit.record(actor, "ROLE_UPDATED", "ROLE", role.getCode(), "Nombre y descripción actualizados.");
		return AdminDtos.RoleSummary.from(role, users.countByRoles_Code(role.getCode()));
	}

	@Transactional
	public void delete(String code, String actor) {
		Role role = managedRole(code);
		ensureCustom(role);
		if (users.existsByRoles_Code(role.getCode())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Quita este rol de las cuentas antes de eliminarlo.");
		roles.delete(role);
		audit.record(actor, "ROLE_DELETED", "ROLE", role.getCode(), "Rol personalizado eliminado.");
	}

	@Transactional
	public AdminDtos.RoleSummary setPermissions(String code, Set<String> permissionCodes, String actor) {
		Role role = managedRole(code);
		ensureCustom(role);
		Set<String> normalizedCodes = permissionCodes.stream().map(value -> value.trim().toUpperCase(java.util.Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
		List<Permission> resolved = normalizedCodes.isEmpty() ? List.of() : permissions.findAllByCodeIn(normalizedCodes);
		if (resolved.size() != normalizedCodes.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uno o más permisos no existen.");
		Set<String> oldCodes = role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toUnmodifiableSet());
		if (!oldCodes.equals(normalizedCodes)) {
			List<String> memberEmails = users.findEmailsByRoleCode(role.getCode());
			role.replacePermissions(new HashSet<>(resolved));
			memberEmails.forEach(sessionInvalidator::invalidate);
			audit.record(actor, "ROLE_PERMISSIONS_CHANGED", "ROLE", role.getCode(), "Permisos asignados: " + String.join(", ", new java.util.TreeSet<>(normalizedCodes)) + ".");
		}
		return AdminDtos.RoleSummary.from(role, users.countByRoles_Code(role.getCode()));
	}

	private Role managedRole(String code) {
		return roles.lockByCode(code.trim().toUpperCase(java.util.Locale.ROOT))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontramos ese rol."));
	}

	private void ensureCustom(Role role) {
		if (role.isSystemManaged()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Los roles de sistema son de solo lectura.");
	}
}
