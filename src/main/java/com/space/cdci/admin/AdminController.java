package com.space.cdci.admin;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
	private final UserAdminService userAdminService;
	private final RoleAdminService roleAdminService;
	private final AccessAuditService audit;

	public AdminController(UserAdminService userAdminService, RoleAdminService roleAdminService, AccessAuditService audit) {
		this.userAdminService = userAdminService;
		this.roleAdminService = roleAdminService;
		this.audit = audit;
	}

	@GetMapping("/users")
	@PreAuthorize("hasAuthority('USER_READ')")
	public AdminDtos.PageResponse<AdminDtos.UserSummary> users(
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return userAdminService.list(search, page, size);
	}

	@PatchMapping("/users/{id}/status")
	@PreAuthorize("hasAuthority('USER_MANAGE')")
	public AdminDtos.UserSummary userStatus(@PathVariable UUID id, @Valid @RequestBody AdminDtos.SetUserStatusRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) {
		if (request.enabled() == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica si la cuenta debe estar habilitada.");
		return userAdminService.changeStatus(id, request.enabled(), actor);
	}

	@PutMapping("/users/{id}/roles")
	@PreAuthorize("hasAuthority('USER_ROLE_ASSIGN')")
	public AdminDtos.UserSummary userRoles(@PathVariable UUID id, @Valid @RequestBody AdminDtos.SetUserRolesRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return userAdminService.setRoles(id, request.roleCodes(), actor);
	}

	@GetMapping("/roles")
	@PreAuthorize("hasAuthority('ROLE_READ')")
	public List<AdminDtos.RoleSummary> roles() { return roleAdminService.listRoles(); }

	@PostMapping("/roles")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('ROLE_MANAGE')")
	public AdminDtos.RoleSummary createRole(@Valid @RequestBody AdminDtos.CreateRoleRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return roleAdminService.create(request, actor.email());
	}

	@PutMapping("/roles/{code}")
	@PreAuthorize("hasAuthority('ROLE_MANAGE')")
	public AdminDtos.RoleSummary updateRole(@PathVariable String code, @Valid @RequestBody AdminDtos.UpdateRoleRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return roleAdminService.update(code, request, actor.email());
	}

	@PutMapping("/roles/{code}/permissions")
	@PreAuthorize("hasAuthority('ROLE_MANAGE')")
	public AdminDtos.RoleSummary rolePermissions(@PathVariable String code, @Valid @RequestBody AdminDtos.SetPermissionsRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return roleAdminService.setPermissions(code, request.permissionCodes(), actor.email());
	}

	@DeleteMapping("/roles/{code}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('ROLE_MANAGE')")
	public void deleteRole(@PathVariable String code, @AuthenticationPrincipal CdciPrincipal actor) {
		roleAdminService.delete(code, actor.email());
	}

	@GetMapping("/permissions")
	@PreAuthorize("hasAuthority('ROLE_READ')")
	public List<AdminDtos.PermissionSummary> permissions() { return roleAdminService.listPermissions(); }

	@GetMapping("/audit")
	@PreAuthorize("hasAuthority('AUDIT_READ')")
	public AdminDtos.PageResponse<AdminDtos.AuditSummary> auditEvents(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), Sort.by(Sort.Direction.DESC, "createdAt"));
		return AdminDtos.PageResponse.from(audit.page(pageable), AdminDtos.AuditSummary::from);
	}
}
