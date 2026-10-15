package com.space.cdci.pipelines;

import com.space.cdci.identity.UserAccount;
import com.space.cdci.projects.LinkedRepository;
import com.space.cdci.projects.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipelines")
public class PipelineDefinition {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_repository_id") private LinkedRepository repository;
	@Column(nullable = false, length = 100) private String name;
	@Column(name = "current_version", nullable = false) private int currentVersion;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected PipelineDefinition() {}

	public PipelineDefinition(Project project, LinkedRepository repository, String name, UserAccount createdBy) {
		this.id = UUID.randomUUID();
		this.project = project;
		this.repository = repository;
		this.name = name;
		this.currentVersion = 1;
		this.createdBy = createdBy;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public int nextVersion(String newName) {
		this.currentVersion += 1;
		this.name = newName;
		this.updatedAt = Instant.now();
		return this.currentVersion;
	}

	public UUID getId() { return id; }
	public Project getProject() { return project; }
	public LinkedRepository getRepository() { return repository; }
	public String getName() { return name; }
	public int getCurrentVersion() { return currentVersion; }
	public UserAccount getCreatedBy() { return createdBy; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
