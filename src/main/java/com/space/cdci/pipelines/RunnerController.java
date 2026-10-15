package com.space.cdci.pipelines;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
	private final RunnerTokenVerifier tokens;

	public RunnerController(PipelineRunService runs, RunnerTokenVerifier tokens) { this.runs = runs; this.tokens = tokens; }

	@PostMapping("/claim")
	public ResponseEntity<PipelineDtos.WorkerClaim> claim(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody PipelineDtos.WorkerClaimRequest request) {
		tokens.require(authorization);
		PipelineDtos.WorkerClaim claim = runs.claim(request.workerId());
		return claim == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(claim);
	}

	@PostMapping("/runs/{runId}/logs")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void log(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDtos.WorkerLogRequest request) {
		tokens.require(authorization);
		runs.appendWorkerLog(runId, request);
	}

	@PostMapping("/runs/{runId}/complete")
	public PipelineDtos.RunSummary complete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable UUID runId, @Valid @RequestBody PipelineDtos.WorkerCompleteRequest request) {
		tokens.require(authorization);
		return runs.completeFromWorker(runId, request);
	}
}
