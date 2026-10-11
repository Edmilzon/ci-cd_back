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

	public record UserResponse(UUID id, String email, String fullName, Set<String> roles, Instant createdAt) {
		public static UserResponse from(UserAccount user) {
			return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),
					user.getRoles().stream().map(Role::getCode).collect(java.util.stream.Collectors.toUnmodifiableSet()), user.getCreatedAt());
		}
	}

	public record CsrfResponse(String token) {}
	public record MessageResponse(String message) {}
}
