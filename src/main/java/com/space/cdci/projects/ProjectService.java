package com.space.cdci.projects;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import com.space.cdci.integrations.github.GitHubIntegrationService;
import com.space.cdci.pipelines.PipelineDefinitionRepository;
import jakarta.validation.Valid;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {
	private final ProjectRepository projects;
	private final ProjectMemberRepository members;
	private final LinkedRepositoryRepository repositories;
	private final PipelineDefinitionRepository pipelines;
	private final UserRepository users;
	private final ProjectAccessService access;
	private final GitHubIntegrationService github;
	private final AccessAuditService audit;

	public ProjectService(ProjectRepository projects, ProjectMemberRepository members, LinkedRepositoryRepository repositories,
			PipelineDefinitionRepository pipelines,
			UserRepository users, ProjectAccessService access, GitHubIntegrationService github, AccessAuditService audit) {
		this.projects = projects;
		this.members = members;
		this.repositories = repositories;
		this.pipelines = pipelines;
		this.users = users;
		this.access = access;
		this.github = github;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<ProjectDtos.ProjectSummary> list(CdciPrincipal actor) {
		List<Project> visible = access.isProjectAdmin(actor)
				? projects.findByArchivedFalseOrderByUpdatedAtDesc()
				: members.findActiveProjectsByUserId(actor.id());
		return visible.stream().map(project -> summary(project, actor)).toList();
	}

	@Transactional
	public ProjectDtos.ProjectSummary create(@Valid ProjectDtos.CreateProjectRequest request, CdciPrincipal actor) {
		String name = request.name().trim();
		String slug = request.slug() == null || request.slug().isBlank() ? slugify(name) : request.slug().trim().toLowerCase(Locale.ROOT);
		if (slug.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica un nombre que permita crear un identificador de proyecto.");
		if (projects.existsBySlugIgnoreCase(slug)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un proyecto con ese identificador.");
		UserAccount owner = user(actor.id());
		Project project = projects.save(new Project(slug, name, valueOrEmpty(request.description()), owner));
		members.save(new ProjectMember(project, owner, ProjectAccessLevel.OWNER, owner));
		audit.record(actor.email(), "PROJECT_CREATED", "PROJECT", project.getId().toString(), "Creó el proyecto " + project.getName() + ".");
		return summary(project, actor);
	}

	@Transactional(readOnly = true)
	public ProjectDtos.ProjectSummary get(UUID id, CdciPrincipal actor) {
		return summary(access.requireRead(id, actor), actor);
	}

	@Transactional
	public ProjectDtos.ProjectSummary update(UUID id, ProjectDtos.UpdateProjectRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(id, actor);
		String name = request.name().trim();
		project.update(name, valueOrEmpty(request.description()));
		audit.record(actor.email(), "PROJECT_UPDATED", "PROJECT", id.toString(), "Actualizó el proyecto " + project.getName() + ".");
		return summary(project, actor);
	}

	@Transactional
	public void archive(UUID id, CdciPrincipal actor) {
		Project project = access.requireOwner(id, actor);
		if (repositories.countByProject_Id(id) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Desconecta primero los repositorios para archivar este proyecto.");
		project.archive();
		audit.record(actor.email(), "PROJECT_ARCHIVED", "PROJECT", id.toString(), "Archivó el proyecto " + project.getName() + ".");
	}

	@Transactional(readOnly = true)
	public List<ProjectDtos.ProjectMemberSummary> listMembers(UUID projectId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		return members.findAllByProject_IdOrderByCreatedAtAsc(projectId).stream().map(ProjectService::memberSummary).toList();
	}

	@Transactional
	public ProjectDtos.ProjectMemberSummary addMember(UUID projectId, ProjectDtos.AddMemberRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		ProjectAccessLevel requested = request.accessLevel();
		ProjectMember actorMembership = members.findByProject_IdAndUser_Id(projectId, actor.id()).orElse(null);
		if (requested == ProjectAccessLevel.OWNER && !access.isProjectAdmin(actor)
				&& (actorMembership == null || actorMembership.getAccessLevel() != ProjectAccessLevel.OWNER)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo una persona propietaria puede asignar ese nivel de acceso.");
		}
		UserAccount target = users.findByEmailIgnoreCase(request.email().trim()).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe una cuenta registrada con ese correo."));
		if (!target.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede agregar una cuenta suspendida.");
		if (members.existsByProject_IdAndUser_Id(projectId, target.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "La persona ya pertenece al proyecto.");
		ProjectMember added = members.save(new ProjectMember(project, target, requested, user(actor.id())));
		project.touch();
		audit.record(actor.email(), "PROJECT_MEMBER_ADDED", "PROJECT_MEMBER", added.getId().toString(), "Agregó " + target.getEmail() + " al proyecto " + project.getName() + ".");
		return memberSummary(added);
	}

	@Transactional
	public ProjectDtos.ProjectMemberSummary updateMember(UUID projectId, UUID memberUserId, ProjectDtos.UpdateMemberRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		ProjectMember target = members.findByProject_IdAndUser_Id(projectId, memberUserId).orElseThrow(ProjectService::notFound);
		ProjectMember actorMembership = members.findByProject_IdAndUser_Id(projectId, actor.id()).orElse(null);
		boolean owner = access.isProjectAdmin(actor) || (actorMembership != null && actorMembership.getAccessLevel() == ProjectAccessLevel.OWNER);
		if (!owner && (target.getAccessLevel() == ProjectAccessLevel.OWNER || target.getAccessLevel() == ProjectAccessLevel.MAINTAINER
				|| request.accessLevel() == ProjectAccessLevel.OWNER || request.accessLevel() == ProjectAccessLevel.MAINTAINER)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo una persona propietaria puede cambiar accesos de mantenimiento.");
		}
		if (target.getAccessLevel() == ProjectAccessLevel.OWNER && request.accessLevel() != ProjectAccessLevel.OWNER
				&& members.countByProject_IdAndAccessLevel(projectId, ProjectAccessLevel.OWNER) <= 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El proyecto debe conservar al menos una persona propietaria.");
		}
		target.setAccessLevel(request.accessLevel());
		project.touch();
		audit.record(actor.email(), "PROJECT_MEMBER_UPDATED", "PROJECT_MEMBER", target.getId().toString(), "Cambió el acceso de " + target.getUser().getEmail() + " en " + project.getName() + ".");
		return memberSummary(target);
	}

	@Transactional
	public void removeMember(UUID projectId, UUID memberUserId, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		ProjectMember target = members.findByProject_IdAndUser_Id(projectId, memberUserId).orElseThrow(ProjectService::notFound);
		ProjectMember actorMembership = members.findByProject_IdAndUser_Id(projectId, actor.id()).orElse(null);
		boolean owner = access.isProjectAdmin(actor) || (actorMembership != null && actorMembership.getAccessLevel() == ProjectAccessLevel.OWNER);
		if (!owner && (target.getAccessLevel() == ProjectAccessLevel.OWNER || target.getAccessLevel() == ProjectAccessLevel.MAINTAINER)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo una persona propietaria puede quitar estos accesos.");
		}
		if (target.getAccessLevel() == ProjectAccessLevel.OWNER && members.countByProject_IdAndAccessLevel(projectId, ProjectAccessLevel.OWNER) <= 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El proyecto debe conservar al menos una persona propietaria.");
		}
		String email = target.getUser().getEmail();
		members.delete(target);
		project.touch();
		audit.record(actor.email(), "PROJECT_MEMBER_REMOVED", "PROJECT_MEMBER", target.getId().toString(), "Quitó a " + email + " del proyecto " + project.getName() + ".");
	}

	@Transactional(readOnly = true)
	public List<ProjectDtos.RepositorySummary> listRepositories(UUID projectId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		return repositories.findAllByProject_IdOrderByCreatedAtDesc(projectId).stream().map(ProjectService::repositorySummary).toList();
	}

	@Transactional
	public ProjectDtos.RepositorySummary addRepository(UUID projectId, ProjectDtos.AddRepositoryRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		if (repositories.existsByProject_IdAndGithubRepositoryId(projectId, request.githubRepositoryId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese repositorio ya está conectado al proyecto.");
		}
		GitHubIntegrationService.GitHubRepositoryData data = github.requireRepository(actor, request.githubRepositoryId());
		ProjectDtos.RepositoryCatalogItem repo = data.repository();
		String description = repo.description().length() > 1000 ? repo.description().substring(0, 1000) : repo.description();
		LinkedRepository linked = repositories.save(new LinkedRepository(project, data.connection(), repo.id(), repo.fullName(),
			repo.htmlUrl(), repo.defaultBranch(), repo.privateRepository(), description, user(actor.id())));
		project.touch();
		audit.record(actor.email(), "PROJECT_REPOSITORY_CONNECTED", "PROJECT_REPOSITORY", linked.getId().toString(), "Conectó " + repo.fullName() + " al proyecto " + project.getName() + ".");
		return repositorySummary(linked);
	}

	@Transactional
	public void removeRepository(UUID projectId, UUID linkedRepositoryId, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		LinkedRepository linked = repositories.findById(linkedRepositoryId).filter(repo -> repo.getProject().getId().equals(projectId)).orElseThrow(ProjectService::notFound);
		if (pipelines.countByRepository_Id(linkedRepositoryId) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Elimina primero los pipelines asociados a este repositorio.");
		repositories.delete(linked);
		project.touch();
		audit.record(actor.email(), "PROJECT_REPOSITORY_REMOVED", "PROJECT_REPOSITORY", linked.getId().toString(), "Desconectó " + linked.getFullName() + " del proyecto " + project.getName() + ".");
	}

	private ProjectDtos.ProjectSummary summary(Project project, CdciPrincipal actor) {
		ProjectMember member = members.findByProject_IdAndUser_Id(project.getId(), actor.id()).orElse(null);
		boolean admin = access.isProjectAdmin(actor);
		String role = member == null ? (admin ? "ADMIN" : "VIEWER") : member.getAccessLevel().name();
		boolean manage = admin || (member != null && (member.getAccessLevel() == ProjectAccessLevel.OWNER || member.getAccessLevel() == ProjectAccessLevel.MAINTAINER));
		boolean archive = admin || (member != null && member.getAccessLevel() == ProjectAccessLevel.OWNER);
		return new ProjectDtos.ProjectSummary(project.getId(), project.getSlug(), project.getName(), project.getDescription(), role,
			manage, archive, repositories.countByProject_Id(project.getId()), members.countByProject_Id(project.getId()), project.getCreatedAt(), project.getUpdatedAt());
	}

	private UserAccount user(UUID id) { return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión nuevamente.")); }
	private static String valueOrEmpty(String value) { return value == null ? "" : value.trim(); }
	private static String slugify(String value) {
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
		return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
	}
	private static ProjectDtos.ProjectMemberSummary memberSummary(ProjectMember member) {
		return new ProjectDtos.ProjectMemberSummary(member.getUser().getId(), member.getUser().getFullName(), member.getUser().getEmail(), member.getAccessLevel().name(), member.getCreatedAt());
	}
	private static ProjectDtos.RepositorySummary repositorySummary(LinkedRepository repository) {
		return new ProjectDtos.RepositorySummary(repository.getId(), repository.getGithubRepositoryId(), repository.getFullName(), repository.getHtmlUrl(),
			repository.getDefaultBranch(), repository.isPrivateRepository(), repository.getDescription(), repository.getGithubConnection().getGithubLogin(), repository.getCreatedAt());
	}
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el recurso del proyecto."); }
}
