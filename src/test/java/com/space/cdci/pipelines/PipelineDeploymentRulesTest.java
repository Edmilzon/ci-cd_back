package com.space.cdci.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.space.cdci.identity.UserAccount;
import com.space.cdci.projects.Project;
import org.junit.jupiter.api.Test;

class PipelineDeploymentRulesTest {
	@Test
	void productionDeploymentRequiresIndependentApprovalBeforeStarting() {
		PipelineRun run = mock(PipelineRun.class);
		PipelineDefinition pipeline = mock(PipelineDefinition.class);
		Project project = mock(Project.class);
		UserAccount requester = mock(UserAccount.class);
		UserAccount reviewer = mock(UserAccount.class);
		when(run.getPipeline()).thenReturn(pipeline);
		when(pipeline.getProject()).thenReturn(project);
		when(run.getRequestedBy()).thenReturn(requester);
		PipelineDeployment deployment = new PipelineDeployment(run, "deploy", "production", "a".repeat(40));

		assertEquals(PipelineDeploymentStatus.WAITING_APPROVAL, deployment.getStatus());
		assertThrows(IllegalStateException.class, () -> deployment.started(42L, "https://github.com/acme/app/deployments/42"));

		deployment.approve(reviewer);
		assertEquals(PipelineDeploymentStatus.APPROVED, deployment.getStatus());
		assertEquals(reviewer, deployment.getApprovedBy());
		deployment.started(42L, "https://github.com/acme/app/deployments/42");
		assertEquals(PipelineDeploymentStatus.IN_PROGRESS, deployment.getStatus());
		deployment.complete(true);
		assertEquals(PipelineDeploymentStatus.SUCCEEDED, deployment.getStatus());
		assertThrows(IllegalStateException.class, () -> deployment.approve(reviewer));
	}

	@Test
	void nonProductionDeploymentStartsApproved() {
		PipelineRun run = mock(PipelineRun.class);
		PipelineDefinition pipeline = mock(PipelineDefinition.class);
		Project project = mock(Project.class);
		when(run.getPipeline()).thenReturn(pipeline);
		when(pipeline.getProject()).thenReturn(project);
		when(run.getRequestedBy()).thenReturn(mock(UserAccount.class));
		PipelineDeployment deployment = new PipelineDeployment(run, "deploy", "staging", "b".repeat(40));

		assertEquals(PipelineDeploymentStatus.APPROVED, deployment.getStatus());
		deployment.started(7L, "https://github.com/acme/app/deployments/7");
		deployment.complete(false);
		assertEquals(PipelineDeploymentStatus.FAILED, deployment.getStatus());
	}
}
