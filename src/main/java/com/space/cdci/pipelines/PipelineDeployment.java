package com.space.cdci.pipelines;

import com.space.cdci.identity.UserAccount;
import com.space.cdci.projects.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_deployments", uniqueConstraints = @UniqueConstraint(name = "uq_pipeline_deployments_run_job", columnNames = {"pipeline_run_id", "job_id"}))
public class PipelineDeployment {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pipeline_run_id") private PipelineRun pipelineRun;
	@Column(name = "job_id", nullable = false, length = 40) private String jobId;
	@Column(name = "environment_name", nullable = false, length = 64) private String environmentName;
	@Column(name = "source_commit_sha", nullable = false, length = 40) private String sourceCommitSha;
	@Column(name = "github_deployment_id") private Long githubDeploymentId;
	@Column(name = "github_html_url", columnDefinition = "text") private String githubHtmlUrl;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private PipelineDeploymentStatus status;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "requested_by_user_id") private UserAccount requestedBy;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "approved_by_user_id") private UserAccount approvedBy;
	@Column(name = "requested_at", nullable = false) private Instant requestedAt;
	@Column(name = "approved_at") private Instant approvedAt;
	@Column(name = "completed_at") private Instant completedAt;

	protected PipelineDeployment() {}

	public PipelineDeployment(PipelineRun run, String jobId, String environmentName, String sourceCommitSha) {
		this.id = UUID.randomUUID();
		this.project = run.getPipeline().getProject();
		this.pipelineRun = run;
		this.jobId = jobId;
		this.environmentName = environmentName;
		this.sourceCommitSha = sourceCommitSha;
		this.requestedBy = run.getRequestedBy();
		this.requestedAt = Instant.now();
		this.status = isProduction(environmentName) ? PipelineDeploymentStatus.WAITING_APPROVAL : PipelineDeploymentStatus.APPROVED;
		if (this.status == PipelineDeploymentStatus.APPROVED) this.approvedAt = this.requestedAt;
	}

	public void approve(UserAccount actor) {
		if (status != PipelineDeploymentStatus.WAITING_APPROVAL) throw new IllegalStateException("El despliegue ya no espera aprobación.");
		this.status = PipelineDeploymentStatus.APPROVED;
		this.approvedBy = actor;
		this.approvedAt = Instant.now();
	}

	public void reject(UserAccount actor) {
		if (status != PipelineDeploymentStatus.WAITING_APPROVAL) throw new IllegalStateException("El despliegue ya no espera aprobación.");
		this.status = PipelineDeploymentStatus.REJECTED;
		this.approvedBy = actor;
		this.approvedAt = Instant.now();
		this.completedAt = this.approvedAt;
	}

	public void fail() {
		if (status == PipelineDeploymentStatus.SUCCEEDED || status == PipelineDeploymentStatus.FAILED || status == PipelineDeploymentStatus.REJECTED) return;
		this.status = PipelineDeploymentStatus.FAILED;
		this.completedAt = Instant.now();
	}

	public void started(long deploymentId, String htmlUrl) {
		if (status != PipelineDeploymentStatus.APPROVED) throw new IllegalStateException("El despliegue todavía no fue aprobado.");
		this.githubDeploymentId = deploymentId;
		this.githubHtmlUrl = htmlUrl;
		this.status = PipelineDeploymentStatus.IN_PROGRESS;
	}

	public void complete(boolean succeeded) {
		if (status != PipelineDeploymentStatus.IN_PROGRESS) return;
		this.status = succeeded ? PipelineDeploymentStatus.SUCCEEDED : PipelineDeploymentStatus.FAILED;
		this.completedAt = Instant.now();
	}

	public UUID getId() { return id; }
	public Project getProject() { return project; }
	public PipelineRun getPipelineRun() { return pipelineRun; }
	public String getJobId() { return jobId; }
	public String getEnvironmentName() { return environmentName; }
	public String getSourceCommitSha() { return sourceCommitSha; }
	public Long getGithubDeploymentId() { return githubDeploymentId; }
	public String getGithubHtmlUrl() { return githubHtmlUrl; }
	public PipelineDeploymentStatus getStatus() { return status; }
	public UserAccount getRequestedBy() { return requestedBy; }
	public UserAccount getApprovedBy() { return approvedBy; }
	public Instant getRequestedAt() { return requestedAt; }
	public Instant getApprovedAt() { return approvedAt; }
	public Instant getCompletedAt() { return completedAt; }
	public static boolean isProduction(String environmentName) { return "production".equalsIgnoreCase(environmentName); }
}
