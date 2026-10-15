package com.space.cdci.projects;

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
@Table(name = "project_secrets", uniqueConstraints = @UniqueConstraint(name = "uq_project_secrets_project_name", columnNames = {"project_id", "name"}))
public class ProjectSecret {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
	@Column(nullable = false, length = 100) private String name;
	@Column(name = "secret_ciphertext", nullable = false, columnDefinition = "text") private String ciphertext;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by_user_id") private UserAccount updatedBy;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected ProjectSecret() {}

	public ProjectSecret(Project project, String name, String ciphertext, UserAccount updatedBy) {
		this.id = UUID.randomUUID();
		this.project = project;
		this.name = name;
		this.ciphertext = ciphertext;
		this.updatedBy = updatedBy;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public void replace(String ciphertext, UserAccount updatedBy) {
		this.ciphertext = ciphertext;
		this.updatedBy = updatedBy;
		this.updatedAt = Instant.now();
	}

	public UUID getId() { return id; }
	public Project getProject() { return project; }
	public String getName() { return name; }
	public String getCiphertext() { return ciphertext; }
	public UserAccount getUpdatedBy() { return updatedBy; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
