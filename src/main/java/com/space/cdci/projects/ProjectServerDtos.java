package com.space.cdci.projects;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ProjectServerDtos {
	private ProjectServerDtos() {}

	public record CreateServerRequest(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 253) String host,
		@NotNull @Min(1) @Max(65535) Integer port,
		@NotBlank @Size(max = 100) String username,
		@NotBlank @Size(max = 50) String hostKeyFingerprint,
		@NotBlank @Size(max = 4096) String password) {}

	public record UpdateServerRequest(
		@NotBlank @Size(max = 100) String name,
		@NotBlank @Size(max = 253) String host,
		@NotNull @Min(1) @Max(65535) Integer port,
		@NotBlank @Size(max = 100) String username,
		@NotBlank @Size(max = 50) String hostKeyFingerprint,
		@Size(max = 4096) String password) {}

	public record ExecuteCommandRequest(@NotBlank @Size(max = 4000) String command) {}

	public record ServerSummary(UUID id, String name, String host, int port, String username, String hostKeyFingerprint, String lastStatus,
			Instant lastConnectedAt, String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) {}

	public record TestConnectionResponse(String status, String message, String osInfo, long latencyMs, ServerSummary server) {}

	public record ExecuteCommandResponse(String command, String output, String errorOutput, int exitStatus, long durationMillis) {}
}
