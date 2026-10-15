package com.space.cdci.pipelines;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public final class PipelineDeploymentDtos {
	private PipelineDeploymentDtos() {}

	public record WorkerRequestDeployment(@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,39}") String jobId,
			@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}") String environmentName,
			@NotBlank @Pattern(regexp = "[a-f0-9]{40}") String commitSha,
			@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId) {}
	public record WorkerAction(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId) {}
	public record WorkerCompleteDeployment(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String workerId,
			@NotNull Boolean succeeded) {}
	public record DeploymentSummary(UUID id, UUID runId, UUID projectId, UUID pipelineId, String projectName,
			String pipelineName, String repositoryName, String branchName, String environmentName, String sourceCommitSha,
			String status, String requestedBy, String approvedBy, Instant requestedAt, Instant approvedAt,
			Instant completedAt, Long githubDeploymentId, String githubHtmlUrl, UUID rollbackOfDeploymentId) {}
	public record ApprovalStatus(boolean approved, boolean rejected, String status) {}
}
