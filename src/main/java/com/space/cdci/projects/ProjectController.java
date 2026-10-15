package com.space.cdci.projects;

import com.space.cdci.identity.CdciPrincipal;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import com.space.cdci.integrations.github.GitHubIntegrationService;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
	private final ProjectService projects;
	private final ProjectSecretService secrets;

	public ProjectController(ProjectService projects, ProjectSecretService secrets) { this.projects = projects; this.secrets = secrets; }

	@GetMapping
	public List<ProjectDtos.ProjectSummary> list(@AuthenticationPrincipal CdciPrincipal actor) { return projects.list(actor); }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectDtos.ProjectSummary create(@Valid @RequestBody ProjectDtos.CreateProjectRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return projects.create(request, actor);
	}

	@GetMapping("/{projectId}")
	public ProjectDtos.ProjectSummary get(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) { return projects.get(projectId, actor); }

	@PutMapping("/{projectId}")
	public ProjectDtos.ProjectSummary update(@PathVariable UUID projectId, @Valid @RequestBody ProjectDtos.UpdateProjectRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return projects.update(projectId, request, actor);
	}

	@DeleteMapping("/{projectId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void archive(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) { projects.archive(projectId, actor); }

	@GetMapping("/{projectId}/members")
	public List<ProjectDtos.ProjectMemberSummary> members(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) { return projects.listMembers(projectId, actor); }

	@PostMapping("/{projectId}/members")
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectDtos.ProjectMemberSummary addMember(@PathVariable UUID projectId, @Valid @RequestBody ProjectDtos.AddMemberRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return projects.addMember(projectId, request, actor);
	}

	@PutMapping("/{projectId}/members/{userId}")
	public ProjectDtos.ProjectMemberSummary updateMember(@PathVariable UUID projectId, @PathVariable UUID userId,
			@Valid @RequestBody ProjectDtos.UpdateMemberRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return projects.updateMember(projectId, userId, request, actor);
	}

	@DeleteMapping("/{projectId}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeMember(@PathVariable UUID projectId, @PathVariable UUID userId, @AuthenticationPrincipal CdciPrincipal actor) { projects.removeMember(projectId, userId, actor); }

	@GetMapping("/{projectId}/repositories")
	public List<ProjectDtos.RepositorySummary> repositories(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) { return projects.listRepositories(projectId, actor); }

	@PostMapping("/{projectId}/repositories")
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectDtos.RepositorySummary addRepository(@PathVariable UUID projectId, @Valid @RequestBody ProjectDtos.AddRepositoryRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return projects.addRepository(projectId, request, actor);
	}

	@DeleteMapping("/{projectId}/repositories/{repositoryId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeRepository(@PathVariable UUID projectId, @PathVariable UUID repositoryId, @AuthenticationPrincipal CdciPrincipal actor) { projects.removeRepository(projectId, repositoryId, actor); }

	@GetMapping("/{projectId}/secrets")
	public List<ProjectSecretDtos.SecretSummary> secrets(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) {
		return secrets.list(projectId, actor);
	}

	@PutMapping("/{projectId}/secrets/{name}")
	public ProjectSecretDtos.SecretSummary saveSecret(@PathVariable UUID projectId, @PathVariable String name,
			@Valid @RequestBody ProjectSecretDtos.WriteSecretRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return secrets.upsert(projectId, name, request, actor);
	}

	@DeleteMapping("/{projectId}/secrets/{name}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSecret(@PathVariable UUID projectId, @PathVariable String name, @AuthenticationPrincipal CdciPrincipal actor) {
		secrets.delete(projectId, name, actor);
	}
}
