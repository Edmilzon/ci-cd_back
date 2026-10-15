package com.space.cdci.pipelines;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runner")
public class RunnerController {
	private final PipelineRunService runs;
	private final PipelineDeploymentService deployments;
	private final RunnerTokenVerifier tokens;

	public RunnerController(PipelineRunService runs, PipelineDeploymentService deployments, RunnerTokenVerifier tokens) {
		this.runs = runs;
		this.deployments = deployments;
		this.tokens = tokens;
	}

	@PostMapping("/claim")
	public ResponseEntity<PipelineDtos.WorkerClaim> claim(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody PipelineDtos.WorkerClaimRequest request) {
		tokens.require(authorization);
		PipelineDtos.WorkerClaim claim = runs.claim(request.workerId());
		return claim == null
				? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
				: ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(claim);
	}

	@PostMapping("/runs/{runId}/logs")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void log(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDtos.WorkerLogRequest request) {
		tokens.require(authorization);
		runs.appendWorkerLog(runId, request);
	}

	@PostMapping("/runs/{runId}/heartbeat")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void heartbeat(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDtos.WorkerHeartbeatRequest request) {
		tokens.require(authorization);
		runs.heartbeat(runId, request);
	}

	@PostMapping("/runs/{runId}/source")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void source(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDtos.WorkerSourceRequest request) {
		tokens.require(authorization);
		runs.pinWorkerSource(runId, request);
	}

	@PostMapping("/runs/{runId}/deployments")
	public PipelineDeploymentDtos.DeploymentSummary requestDeployment(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDeploymentDtos.WorkerRequestDeployment request) {
		tokens.require(authorization);
		return deployments.request(runId, request);
	}

	@PostMapping("/runs/{runId}/deployments/{deploymentId}/approval-status")
	public PipelineDeploymentDtos.ApprovalStatus approvalStatus(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @PathVariable UUID deploymentId, @Valid @RequestBody PipelineDeploymentDtos.WorkerAction request) {
		tokens.require(authorization);
		return deployments.approvalStatus(runId, deploymentId, request);
	}

	@PostMapping("/runs/{runId}/deployments/{deploymentId}/start")
	public PipelineDeploymentDtos.DeploymentSummary startDeployment(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @PathVariable UUID deploymentId, @Valid @RequestBody PipelineDeploymentDtos.WorkerAction request) {
		tokens.require(authorization);
		return deployments.start(runId, deploymentId, request);
	}

	@PostMapping("/runs/{runId}/deployments/{deploymentId}/complete")
	public PipelineDeploymentDtos.DeploymentSummary completeDeployment(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @PathVariable UUID deploymentId, @Valid @RequestBody PipelineDeploymentDtos.WorkerCompleteDeployment request) {
		tokens.require(authorization);
		return deployments.complete(runId, deploymentId, request);
	}

	@PostMapping("/runs/{runId}/complete")
	public PipelineDtos.RunSummary complete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDtos.WorkerCompleteRequest request) {
		tokens.require(authorization);
		return runs.completeFromWorker(runId, request);
	}
}
