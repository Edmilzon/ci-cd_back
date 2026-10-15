package com.space.cdci.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {
	@Mock private ProjectRepository projects;
	@Mock private ProjectMemberRepository members;
	private final UUID userId = UUID.randomUUID();
	private Project project;
	private ProjectAccessService access;

	@BeforeEach
	void setUp() {
		project = new Project("ci-platform", "CI Platform", "", mock(UserAccount.class));
		access = new ProjectAccessService(projects, members);
		when(projects.findByIdAndArchivedFalse(project.getId())).thenReturn(Optional.of(project));
	}

	@Test
	void viewerCanReadButCannotManageOrReadActivity() {
		CdciPrincipal viewer = actor(userId, Set.of(), Set.of());
		when(members.existsByProject_IdAndUser_Id(project.getId(), userId)).thenReturn(true);
		when(members.findByProject_IdAndUser_Id(project.getId(), userId))
				.thenReturn(Optional.of(member(ProjectAccessLevel.VIEWER)));

		assertSame(project, access.requireRead(project.getId(), viewer));
		assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
				() -> access.requireManage(project.getId(), viewer)).getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
				() -> access.requireActivityRead(project.getId(), viewer)).getStatusCode());
	}

	@Test
	void maintainerCanManageButCannotTransferOwnership() {
		CdciPrincipal maintainer = actor(userId, Set.of(), Set.of());
		when(members.findByProject_IdAndUser_Id(project.getId(), userId))
				.thenReturn(Optional.of(member(ProjectAccessLevel.MAINTAINER)));

		assertSame(project, access.requireManage(project.getId(), maintainer));
		assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
				() -> access.requireOwner(project.getId(), maintainer)).getStatusCode());
	}

	@Test
	void projectAdministratorBypassesMembershipChecks() {
		CdciPrincipal admin = actor(userId, Set.of("ADMIN"), Set.of());

		assertSame(project, access.requireOwner(project.getId(), admin));
		verifyNoInteractions(members);
	}

	@Test
	void auditPermissionAllowsActivityReadForProjectMember() {
		CdciPrincipal auditor = actor(userId, Set.of(), Set.of("AUDIT_READ"));
		when(members.existsByProject_IdAndUser_Id(project.getId(), userId)).thenReturn(true);

		assertSame(project, access.requireActivityRead(project.getId(), auditor));
		verify(members).existsByProject_IdAndUser_Id(project.getId(), userId);
	}

	private ProjectMember member(ProjectAccessLevel level) {
		UserAccount user = mock(UserAccount.class);
		return new ProjectMember(project, user, level, user);
	}

	private static CdciPrincipal actor(UUID id, Set<String> roles, Set<String> permissions) {
		CdciPrincipal actor = mock(CdciPrincipal.class);
		when(actor.id()).thenReturn(id);
		when(actor.roleCodes()).thenReturn(roles);
		when(actor.permissionCodes()).thenReturn(permissions);
		return actor;
	}
}
