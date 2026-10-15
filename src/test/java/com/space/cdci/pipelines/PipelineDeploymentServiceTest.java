package com.space.cdci.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import com.space.cdci.integrations.github.GitHubIntegrationService;
import com.space.cdci.projects.ProjectAccessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PipelineDeploymentServiceTest {
	@Mock private PipelineDeploymentRepository deployments;
	@Mock private PipelineRunRepository runs;
	@Mock private PipelineRunLogRepository logs;
	@Mock private PipelineDefinitionRepository pipelines;
	@Mock private ProjectAccessService access;
	@Mock private UserRepository users;
	@Mock private GitHubIntegrationService github;
	@Mock private AccessAuditService audit;

	@Test
	void requesterCannotApproveTheirOwnProductionDeployment() {
		UUID projectId = UUID.randomUUID();
		UUID deploymentId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		CdciPrincipal actor = mock(CdciPrincipal.class);
		UserAccount requester = mock(UserAccount.class);
		PipelineDeployment deployment = mock(PipelineDeployment.class);
		when(actor.id()).thenReturn(requesterId);
		when(deployment.getRequestedBy()).thenReturn(requester);
		when(requester.getId()).thenReturn(requesterId);
		when(deployments.findForUpdate(deploymentId, projectId)).thenReturn(Optional.of(deployment));
		PipelineDeploymentService service = new PipelineDeploymentService(deployments, runs, logs, pipelines, access,
				users, github, audit);

		ResponseStatusException failure = assertThrows(ResponseStatusException.class,
				() -> service.approve(projectId, deploymentId, actor));

		assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
		verify(access).requireManage(projectId, actor);
		verify(users, never()).findById(requesterId);
		verify(deployment, never()).approve(org.mockito.ArgumentMatchers.any());
	}
}
