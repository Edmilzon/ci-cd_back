package com.space.cdci.projects;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_members", uniqueConstraints = @UniqueConstraint(name = "uq_project_members_project_user", columnNames = {"project_id", "user_id"}))
public class ProjectMember {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private UserAccount user;
	@Enumerated(EnumType.STRING) @Column(name = "access_level", nullable = false, length = 16) private ProjectAccessLevel accessLevel;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "added_by_user_id") private UserAccount addedBy;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	protected ProjectMember() {}

	public ProjectMember(Project project, UserAccount user, ProjectAccessLevel accessLevel, UserAccount addedBy) {
		this.id = UUID.randomUUID();
		this.project = project;
		this.user = user;
		this.accessLevel = accessLevel;
		this.addedBy = addedBy;
		this.createdAt = Instant.now();
	}

	public UUID getId() { return id; }
	public Project getProject() { return project; }
	public UserAccount getUser() { return user; }
	public ProjectAccessLevel getAccessLevel() { return accessLevel; }
	public UserAccount getAddedBy() { return addedBy; }
	public Instant getCreatedAt() { return createdAt; }
	public void setAccessLevel(ProjectAccessLevel accessLevel) { this.accessLevel = accessLevel; }
}
