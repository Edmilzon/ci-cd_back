package com.space.cdci.projects;

import com.space.cdci.identity.CdciPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectAccessService {
	private final ProjectRepository projects;
	private final ProjectMemberRepository members;

	public ProjectAccessService(ProjectRepository projects, ProjectMemberRepository members) {
		this.projects = projects;
		this.members = members;
	}

	public Project requireRead(UUID projectId, CdciPrincipal actor) {
		Project project = find(projectId);
		if (isProjectAdmin(actor) || members.existsByProject_IdAndUser_Id(projectId, actor.id())) return project;
		throw notFound();
	}

	public Project requireManage(UUID projectId, CdciPrincipal actor) {
		Project project = find(projectId);
		if (isProjectAdmin(actor)) return project;
		ProjectMember member = members.findByProject_IdAndUser_Id(projectId, actor.id()).orElseThrow(ProjectAccessService::notFound);
		if (member.getAccessLevel() == ProjectAccessLevel.OWNER || member.getAccessLevel() == ProjectAccessLevel.MAINTAINER) return project;
		throw notFound();
	}

	public Project requireOwner(UUID projectId, CdciPrincipal actor) {
		Project project = find(projectId);
		if (isProjectAdmin(actor)) return project;
		ProjectMember member = members.findByProject_IdAndUser_Id(projectId, actor.id()).orElseThrow(ProjectAccessService::notFound);
		if (member.getAccessLevel() == ProjectAccessLevel.OWNER) return project;
		throw notFound();
	}

	public boolean isProjectAdmin(CdciPrincipal actor) { return actor.permissionCodes().contains("PROJECT_ADMIN") || actor.roleCodes().contains("ADMIN"); }

	private Project find(UUID id) { return projects.findByIdAndArchivedFalse(id).orElseThrow(ProjectAccessService::notFound); }
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el proyecto."); }
}
