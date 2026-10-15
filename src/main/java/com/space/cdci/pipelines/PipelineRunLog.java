package com.space.cdci.pipelines;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_run_logs")
public class PipelineRunLog {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "run_id") private PipelineRun run;
	@Column(nullable = false, length = 16) private String stream;
	@Column(nullable = false, columnDefinition = "text") private String message;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	protected PipelineRunLog() {}

	public PipelineRunLog(PipelineRun run, String stream, String message) {
		this.run = run;
		this.stream = stream;
		this.message = message;
		this.createdAt = Instant.now();
	}

	public Long getId() { return id; }
	public UUID getRunId() { return run.getId(); }
	public String getStream() { return stream; }
	public String getMessage() { return message; }
	public Instant getCreatedAt() { return createdAt; }
}
