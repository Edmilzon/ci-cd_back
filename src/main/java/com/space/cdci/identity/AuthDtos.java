package com.space.cdci.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {
	private AuthDtos() {}

	public record RegisterRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 2, max = 80) String fullName,
			@NotBlank @Size(min = 12, max = 128) String password) {}

	public record PasswordChangeRequest(
			@NotBlank @Size(max = 128) String currentPassword,
			@NotBlank @Size(min = 12, max = 128) String newPassword) {}

	public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

	public record PasswordResetConfirmRequest(
			@NotBlank @Size(max = 64) String token,
			@NotBlank @Size(min = 12, max = 128) String newPassword) {}

	public record PasswordResetStatusResponse(boolean enabled) {}
	public record RegistrationStatusResponse(boolean enabled) {}
	public record EmailVerificationRequest(@NotBlank @Email @Size(max = 254) String email) {}
	public record EmailVerificationConfirmRequest(@NotBlank @Size(max = 64) String token) {}
	public record EmailVerificationStatusResponse(boolean required) {}
	public record RegisterResponse(boolean verificationRequired, boolean emailVerified) {}

	public record UserResponse(UUID id, String email, String fullName, boolean emailVerified, Set<String> roles, Set<String> permissions, Instant createdAt) {
		public static UserResponse from(UserAccount user) {
			Set<String> roles = user.getRoles().stream().map(Role::getCode).collect(java.util.stream.Collectors.toUnmodifiableSet());
			Set<String> permissions = user.getRoles().stream().flatMap(role -> role.getPermissions().stream())
					.map(Permission::getCode).collect(java.util.stream.Collectors.toUnmodifiableSet());
			return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.isEmailVerified(), roles, permissions, user.getCreatedAt());
		}
	}

	public record CsrfResponse(String token) {}
	public record MessageResponse(String message) {}
}
