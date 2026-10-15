package com.space.cdci.pipelines;

import com.space.cdci.identity.CdciPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class PipelineRunController {
	private final PipelineRunService runs;

	public PipelineRunController(PipelineRunService runs) { this.runs = runs; }

	@PostMapping("/pipelines/{pipelineId}/runs")
	@ResponseStatus(HttpStatus.CREATED)
	public PipelineDtos.RunSummary queue(@PathVariable UUID projectId, @PathVariable UUID pipelineId,
			@Valid @RequestBody PipelineDtos.QueueRunRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return runs.queue(projectId, pipelineId, request, actor);
	}

	@GetMapping("/pipelines/{pipelineId}/runs")
	public List<PipelineDtos.RunSummary> list(@PathVariable UUID projectId, @PathVariable UUID pipelineId,
			@AuthenticationPrincipal CdciPrincipal actor) { return runs.list(projectId, pipelineId, actor); }

	@GetMapping("/runs/{runId}")
	public PipelineDtos.RunSummary get(@PathVariable UUID projectId, @PathVariable UUID runId,
			@AuthenticationPrincipal CdciPrincipal actor) { return runs.get(projectId, runId, actor); }

	@GetMapping("/runs/{runId}/logs")
	public List<PipelineDtos.RunLog> logs(@PathVariable UUID projectId, @PathVariable UUID runId,
			@RequestParam(defaultValue = "0") long afterId, @AuthenticationPrincipal CdciPrincipal actor) {
		return runs.logs(projectId, runId, afterId, actor);
	}
}
