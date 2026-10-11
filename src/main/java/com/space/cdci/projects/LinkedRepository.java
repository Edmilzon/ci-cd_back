package com.space.cdci.projects;

import com.space.cdci.identity.UserAccount;
import com.space.cdci.integrations.github.GitHubConnection;
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
@Table(name = "project_repositories", uniqueConstraints = @UniqueConstraint(name = "uq_project_repositories_github", columnNames = {"project_id", "github_repository_id"}))
public class LinkedRepository {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "github_connection_id") private GitHubConnection githubConnection;
	@Column(name = "github_repository_id", nullable = false) private long githubRepositoryId;
	@Column(name = "full_name", nullable = false, length = 200) private String fullName;
	@Column(name = "html_url", nullable = false, length = 500) private String htmlUrl;
	@Column(name = "default_branch", nullable = false, length = 255) private String defaultBranch;
	@Column(name = "private_repository", nullable = false) private boolean privateRepository;
	@Column(nullable = false, length = 1000) private String description;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@Column(name = "created_at", nullable = false) private Instant createdAt;

	protected LinkedRepository() {}

	public LinkedRepository(Project project, GitHubConnection connection, long githubRepositoryId, String fullName,
			String htmlUrl, String defaultBranch, boolean privateRepository, String description, UserAccount createdBy) {
		this.id = UUID.randomUUID();
		this.project = project;
		this.githubConnection = connection;
		this.githubRepositoryId = githubRepositoryId;
		this.fullName = fullName;
		this.htmlUrl = htmlUrl;
		this.defaultBranch = defaultBranch;
		this.privateRepository = privateRepository;
		this.description = description;
		this.createdBy = createdBy;
		this.createdAt = Instant.now();
	}

	public UUID getId() { return id; }
	public Project getProject() { return project; }
	public GitHubConnection getGithubConnection() { return githubConnection; }
	public long getGithubRepositoryId() { return githubRepositoryId; }
	public String getFullName() { return fullName; }
	public String getHtmlUrl() { return htmlUrl; }
	public String getDefaultBranch() { return defaultBranch; }
	public boolean isPrivateRepository() { return privateRepository; }
	public String getDescription() { return description; }
	public Instant getCreatedAt() { return createdAt; }
}
