package com.space.cdci.pipelines;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PipelineDtos {
	private PipelineDtos() {}

	public record CreatePipelineRequest(@NotNull UUID repositoryId, @NotBlank @Size(max = 65536) String yaml) {}
	public record AddVersionRequest(@NotBlank @Size(max = 65536) String yaml) {}
	public record ValidateRequest(@NotBlank @Size(max = 65536) String yaml) {}
	public record ValidationResult(boolean valid, String name, int jobs, int steps, List<String> errors) {}
	public record PipelineSummary(UUID id, UUID repositoryId, String repositoryName, String name, int currentVersion,
			Instant createdAt, Instant updatedAt) {}
	public record VersionSummary(int versionNumber, String sha256, String createdBy, Instant createdAt) {}
	public record VersionDetail(int versionNumber, String sha256, String createdBy, Instant createdAt, String yaml) {}
}
