package com.space.cdci.pipelines;

import com.space.cdci.identity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_runs")
public class PipelineRun {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pipeline_id") private PipelineDefinition pipeline;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pipeline_version_id") private PipelineVersion pipelineVersion;
	@Column(name = "branch_name", nullable = false, length = 255) private String branchName;
	@Column(name = "trigger_type", nullable = false, length = 16) private String triggerType;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PipelineRunStatus status;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "requested_by_user_id") private UserAccount requestedBy;
	@Column(name = "requested_at", nullable = false) private Instant requestedAt;
	@Column(name = "started_at") private Instant startedAt;
	@Column(name = "completed_at") private Instant completedAt;
	@Column(name = "failure_message", columnDefinition = "text") private String failureMessage;

	protected PipelineRun() {}

	public PipelineRun(PipelineDefinition pipeline, PipelineVersion pipelineVersion, String branchName, UserAccount requestedBy) {
		this.id = UUID.randomUUID();
		this.pipeline = pipeline;
		this.pipelineVersion = pipelineVersion;
		this.branchName = branchName;
		this.triggerType = "MANUAL";
		this.status = PipelineRunStatus.QUEUED;
		this.requestedBy = requestedBy;
		this.requestedAt = Instant.now();
	}

	public void claim() {
		if (status != PipelineRunStatus.QUEUED) throw new IllegalStateException("La ejecución ya no está en cola.");
		this.status = PipelineRunStatus.RUNNING;
		this.startedAt = Instant.now();
	}

	public void complete(boolean succeeded, String failureMessage) {
		if (status != PipelineRunStatus.RUNNING) throw new IllegalStateException("Solo una ejecución activa puede finalizar.");
		this.status = succeeded ? PipelineRunStatus.SUCCEEDED : PipelineRunStatus.FAILED;
		this.failureMessage = succeeded ? null : failureMessage;
		this.completedAt = Instant.now();
	}

	public UUID getId() { return id; }
	public PipelineDefinition getPipeline() { return pipeline; }
	public PipelineVersion getPipelineVersion() { return pipelineVersion; }
	public String getBranchName() { return branchName; }
	public String getTriggerType() { return triggerType; }
	public PipelineRunStatus getStatus() { return status; }
	public UserAccount getRequestedBy() { return requestedBy; }
	public Instant getRequestedAt() { return requestedAt; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getCompletedAt() { return completedAt; }
	public String getFailureMessage() { return failureMessage; }
}
