package com.space.cdci.pipelines;

import com.space.cdci.identity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_versions", uniqueConstraints = @UniqueConstraint(name = "uq_pipeline_versions_number", columnNames = {"pipeline_id", "version_number"}))
public class PipelineVersion {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pipeline_id") private PipelineDefinition pipeline;
	@Column(name = "version_number", nullable = false) private int versionNumber;
	@Column(name = "yaml_definition", nullable = false, columnDefinition = "text") private String yamlDefinition;
	@Column(nullable = false, length = 64) private String sha256;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	protected PipelineVersion() {}

	public PipelineVersion(PipelineDefinition pipeline, int versionNumber, String yamlDefinition, String sha256, UserAccount createdBy) {
		this.id = UUID.randomUUID();
		this.pipeline = pipeline;
		this.versionNumber = versionNumber;
		this.yamlDefinition = yamlDefinition;
		this.sha256 = sha256;
		this.createdBy = createdBy;
		this.createdAt = Instant.now();
	}

	public UUID getId() { return id; }
	public PipelineDefinition getPipeline() { return pipeline; }
	public int getVersionNumber() { return versionNumber; }
	public String getYamlDefinition() { return yamlDefinition; }
	public String getSha256() { return sha256; }
	public UserAccount getCreatedBy() { return createdBy; }
	public Instant getCreatedAt() { return createdAt; }
}
