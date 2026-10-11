package com.space.cdci.admin;

import com.space.cdci.identity.AccessAuditEvent;
import com.space.cdci.identity.Permission;
import com.space.cdci.identity.Role;
import com.space.cdci.identity.UserAccount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class AdminDtos {
	private AdminDtos() {}

	public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
		public static <T, R> PageResponse<R> from(Page<T> page, java.util.function.Function<T, R> mapper) {
			return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
		}
	}

	public record UserSummary(UUID id, String email, String fullName, boolean enabled, Set<String> roles, Instant createdAt) {
		public static UserSummary from(UserAccount user) {
			return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.isEnabled(),
					user.getRoles().stream().map(Role::getCode).collect(java.util.stream.Collectors.toUnmodifiableSet()), user.getCreatedAt());
		}
	}

	public record PermissionSummary(String code, String name, String description) {
		public static PermissionSummary from(Permission permission) { return new PermissionSummary(permission.getCode(), permission.getName(), permission.getDescription()); }
	}

	public record RoleSummary(String code, String name, String description, boolean systemManaged, List<PermissionSummary> permissions, long userCount) {
		public static RoleSummary from(Role role, long userCount) {
			return new RoleSummary(role.getCode(), role.getName(), role.getDescription(), role.isSystemManaged(),
					role.getPermissions().stream().sorted(java.util.Comparator.comparing(Permission::getCode)).map(PermissionSummary::from).toList(), userCount);
		}
	}

	public record CreateRoleRequest(
			@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,47}$") String code,
			@NotBlank @Size(min = 2, max = 80) String name,
			@NotBlank @Size(max = 240) String description) {}

	public record UpdateRoleRequest(
			@NotBlank @Size(min = 2, max = 80) String name,
			@NotBlank @Size(max = 240) String description) {}

	public record SetPermissionsRequest(@NotEmpty Set<@Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String> permissionCodes) {}
	public record SetUserRolesRequest(@NotEmpty Set<@Pattern(regexp = "^[A-Z][A-Z0-9_]{1,47}$") String> roleCodes) {}
	public record SetUserStatusRequest(Boolean enabled) {}

	public record AuditSummary(UUID id, String actorEmail, String action, String targetType, String targetId, String summary, Instant createdAt) {
		public static AuditSummary from(AccessAuditEvent event) {
			return new AuditSummary(event.getId(), event.getActorEmail(), event.getAction(), event.getTargetType(), event.getTargetId(), event.getSummary(), event.getCreatedAt());
		}
	}
}
