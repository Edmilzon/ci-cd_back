package com.space.cdci.projects;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProjectDtos {
	private ProjectDtos() {}

	public record CreateProjectRequest(
		@NotBlank @Size(max = 100) String name,
		@Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Usa minúsculas, números y guiones.") String slug,
		@Size(max = 1000) String description) {}
	public record UpdateProjectRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {}
	public record AddMemberRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull ProjectAccessLevel accessLevel) {}
	public record UpdateMemberRequest(@NotNull ProjectAccessLevel accessLevel) {}
	public record AddRepositoryRequest(@NotNull Long githubRepositoryId) {}

	public record ProjectSummary(UUID id, String slug, String name, String description, String accessLevel,
			boolean canManage, boolean canArchive, long repositoryCount, long memberCount, Instant createdAt, Instant updatedAt) {}
	public record ProjectMemberSummary(UUID userId, String fullName, String email, String accessLevel, Instant addedAt) {}
	public record RepositorySummary(UUID id, long githubRepositoryId, String fullName, String htmlUrl,
			String defaultBranch, boolean privateRepository, String description, String githubLogin, Instant createdAt) {}
	public record RepositoryCatalogItem(long id, String fullName, String htmlUrl, String defaultBranch,
			boolean privateRepository, String description, String language, Instant pushedAt) {}
	public record GitHubConnectionStatus(boolean connected, String githubLogin, Instant accessTokenExpiresAt) {}
	public record GitHubRepositoryPage(List<RepositoryCatalogItem> items, int page, int pageSize, boolean hasMore) {}
	public record GitHubConnectUrl(String authorizationUrl) {}
}
