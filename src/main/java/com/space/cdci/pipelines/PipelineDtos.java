package com.space.cdci.pipelines;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PipelineDtos {
	private PipelineDtos() {}

	public record CreatePipelineRequest(@NotNull UUID repositoryId, @NotBlank @Size(max = 65536) String yaml) {}
	public record AddVersionRequest(@NotBlank @Size(max = 65536) String yaml) {}
	public record ValidateRequest(@NotBlank @Size(max = 65536) String yaml) {}
	public record QueueRunRequest(@Size(max = 255) String branchName) {}
	public record ValidationResult(boolean valid, String name, int jobs, int steps, List<String> errors) {}
	public record PipelineSummary(UUID id, UUID repositoryId, String repositoryName, String name, int currentVersion,
			Instant createdAt, Instant updatedAt) {}
	public record VersionSummary(int versionNumber, String sha256, String createdBy, Instant createdAt) {}
	public record VersionDetail(int versionNumber, String sha256, String createdBy, Instant createdAt, String yaml) {}
	public record RunSummary(UUID id, UUID pipelineId, String pipelineName, int pipelineVersion, String branchName,
			String triggerType, String status, String requestedBy, Instant requestedAt, Instant startedAt, Instant completedAt,
			String failureMessage) {}
	public record RunLog(long id, String stream, String message, Instant createdAt) {}
	public record WorkerClaimRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId) {}
	public record WorkerLogRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId,
			@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "STDOUT|STDERR|SYSTEM") String stream,
			@NotBlank @Size(max = 8000) String message) {}
	public record WorkerCompleteRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId,
			@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "SUCCEEDED|FAILED") String status,
			@Size(max = 2000) String failureMessage) {}
	public record WorkerHeartbeatRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId) {}
	public record WorkerSourceRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{40}") String commitSha,
			@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId) {}
	public record WorkerClaim(UUID runId, UUID pipelineId, String pipelineName, int version, String branchName,
			String repositoryName, String repositoryCloneUrl, String githubAccessToken, Map<String, String> secrets,
			String sourceCommitSha, String rollbackEnvironment, String yaml, Instant claimedAt) {}
}
