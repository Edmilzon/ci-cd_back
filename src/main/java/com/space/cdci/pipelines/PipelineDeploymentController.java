package com.space.cdci.pipelines;

import com.space.cdci.identity.CdciPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/deployments")
public class PipelineDeploymentController {
	private final PipelineDeploymentService deployments;

	public PipelineDeploymentController(PipelineDeploymentService deployments) { this.deployments = deployments; }

	@GetMapping
	public List<PipelineDeploymentDtos.DeploymentSummary> list(@PathVariable UUID projectId,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return deployments.list(projectId, actor);
	}

	@PostMapping("/{deploymentId}/approve")
	public PipelineDeploymentDtos.DeploymentSummary approve(@PathVariable UUID projectId, @PathVariable UUID deploymentId,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return deployments.approve(projectId, deploymentId, actor);
	}

	@PostMapping("/{deploymentId}/reject")
	public PipelineDeploymentDtos.DeploymentSummary reject(@PathVariable UUID projectId, @PathVariable UUID deploymentId,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return deployments.reject(projectId, deploymentId, actor);
	}

	@PostMapping("/{deploymentId}/rollback")
	@ResponseStatus(HttpStatus.CREATED)
	public PipelineDtos.RunSummary rollback(@PathVariable UUID projectId, @PathVariable UUID deploymentId,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return deployments.rollback(projectId, deploymentId, actor);
	}
}
