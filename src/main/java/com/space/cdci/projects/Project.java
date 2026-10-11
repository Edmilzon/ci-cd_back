package com.space.cdci.projects;

import com.space.cdci.identity.UserAccount;
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
@Table(name = "projects")
public class Project {
	@Id private UUID id;
	@Column(nullable = false, length = 100) private String slug;
	@Column(nullable = false, length = 100) private String name;
	@Column(nullable = false, length = 1000) private String description;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@Column(nullable = false) private boolean archived;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected Project() {}

	public Project(String slug, String name, String description, UserAccount createdBy) {
		this.id = UUID.randomUUID();
		this.slug = slug;
		this.name = name;
		this.description = description;
		this.createdBy = createdBy;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public UUID getId() { return id; }
	public String getSlug() { return slug; }
	public String getName() { return name; }
	public String getDescription() { return description; }
	public UserAccount getCreatedBy() { return createdBy; }
	public boolean isArchived() { return archived; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public void update(String name, String description) { this.name = name; this.description = description; this.updatedAt = Instant.now(); }
	public void archive() { this.archived = true; this.updatedAt = Instant.now(); }
	public void touch() { this.updatedAt = Instant.now(); }
}
