package com.space.cdci.admin;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.Role;
import com.space.cdci.identity.RoleRepository;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAdminService {
	private final UserRepository users;
	private final RoleRepository roles;
	private final SessionInvalidator sessionInvalidator;
	private final AccessAuditService audit;

	public UserAdminService(UserRepository users, RoleRepository roles, SessionInvalidator sessionInvalidator, AccessAuditService audit) {
		this.users = users;
		this.roles = roles;
		this.sessionInvalidator = sessionInvalidator;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public AdminDtos.PageResponse<AdminDtos.UserSummary> list(String search, int requestedPage, int requestedSize) {
		Pageable pageable = PageRequest.of(Math.max(0, requestedPage), Math.max(1, Math.min(requestedSize, 100)), Sort.by(Sort.Direction.DESC, "createdAt"));
		String query = search == null ? "" : search.trim();
		Page<UserAccount> page = query.isEmpty() ? users.searchAll(pageable) : users.search(query, pageable);
		return AdminDtos.PageResponse.from(page, AdminDtos.UserSummary::from);
	}

	@Transactional
	public AdminDtos.UserSummary changeStatus(UUID userId, boolean enabled, CdciPrincipal actor) {
		roles.lockByCode("ADMIN").orElseThrow(() -> new IllegalStateException("Falta el rol de sistema ADMIN."));
		UserAccount target = users.findWithAccessById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontramos la cuenta."));
		if (target.isEnabled() == enabled) return AdminDtos.UserSummary.from(target);
		if (!enabled && actor.id().equals(target.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes suspender tu propia cuenta.");
		if (!enabled && target.isEnabled() && hasRole(target, "ADMIN")) ensureAnotherEnabledAdminRemains();
		target.setEnabled(enabled);
		sessionInvalidator.invalidate(target.getEmail());
		audit.record(actor.email(), enabled ? "USER_ENABLED" : "USER_DISABLED", "USER", userId.toString(), enabled ? "Cuenta habilitada." : "Cuenta suspendida.");
		return AdminDtos.UserSummary.from(target);
	}

	@Transactional
	public AdminDtos.UserSummary setRoles(UUID userId, Set<String> roleCodes, CdciPrincipal actor) {
		if (actor.id().equals(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes cambiar tus propios roles.");
		roles.lockByCode("ADMIN").orElseThrow(() -> new IllegalStateException("Falta el rol de sistema ADMIN."));
		UserAccount target = users.findWithAccessById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontramos la cuenta."));
		Set<String> normalizedCodes = roleCodes.stream().map(code -> code.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
		Set<Role> requestedRoles = new HashSet<>(roles.findAllByCodeIn(normalizedCodes));
		if (requestedRoles.size() != normalizedCodes.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uno o más roles no existen.");
		boolean currentlyAdmin = hasRole(target, "ADMIN");
		boolean willBeAdmin = requestedRoles.stream().anyMatch(role -> role.getCode().equals("ADMIN"));
		if (target.isEnabled() && currentlyAdmin && !willBeAdmin) ensureAnotherEnabledAdminRemains();
		Set<String> oldCodes = target.getRoles().stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
		Set<String> newCodes = requestedRoles.stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
		if (oldCodes.equals(newCodes)) return AdminDtos.UserSummary.from(target);
		target.setRoles(requestedRoles);
		sessionInvalidator.invalidate(target.getEmail());
		audit.record(actor.email(), "USER_ROLES_CHANGED", "USER", userId.toString(), "Roles asignados: " + String.join(", ", new java.util.TreeSet<>(newCodes)) + ".");
		return AdminDtos.UserSummary.from(target);
	}

	private boolean hasRole(UserAccount user, String code) {
		return user.getRoles().stream().anyMatch(role -> role.getCode().equals(code));
	}

	private void ensureAnotherEnabledAdminRemains() {
		if (users.countByEnabledTrueAndRoles_Code("ADMIN") <= 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Debe quedar al menos una cuenta administradora activa.");
		}
	}
}
