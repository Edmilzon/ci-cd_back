package com.space.cdci.pipelines;

import com.space.cdci.identity.CdciPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/pipelines")
public class PipelineController {
	private final PipelineService pipelines;

	public PipelineController(PipelineService pipelines) { this.pipelines = pipelines; }

	@GetMapping
	public List<PipelineDtos.PipelineSummary> list(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) {
		return pipelines.list(projectId, actor);
	}

	@PostMapping("/validate")
	public PipelineDtos.ValidationResult validate(@PathVariable UUID projectId, @Valid @RequestBody PipelineDtos.ValidateRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) { return pipelines.validate(projectId, request, actor); }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PipelineDtos.PipelineSummary create(@PathVariable UUID projectId, @Valid @RequestBody PipelineDtos.CreatePipelineRequest request,
			@AuthenticationPrincipal CdciPrincipal actor) { return pipelines.create(projectId, request, actor); }

	@PostMapping("/{pipelineId}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	public PipelineDtos.VersionSummary addVersion(@PathVariable UUID projectId, @PathVariable UUID pipelineId,
			@Valid @RequestBody PipelineDtos.AddVersionRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return pipelines.addVersion(projectId, pipelineId, request, actor);
	}

	@GetMapping("/{pipelineId}/versions")
	public List<PipelineDtos.VersionSummary> versions(@PathVariable UUID projectId, @PathVariable UUID pipelineId,
			@AuthenticationPrincipal CdciPrincipal actor) { return pipelines.listVersions(projectId, pipelineId, actor); }

	@GetMapping("/{pipelineId}/versions/{number}")
	public PipelineDtos.VersionDetail version(@PathVariable UUID projectId, @PathVariable UUID pipelineId, @PathVariable int number,
			@AuthenticationPrincipal CdciPrincipal actor) { return pipelines.getVersion(projectId, pipelineId, number, actor); }

	@DeleteMapping("/{pipelineId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID projectId, @PathVariable UUID pipelineId, @AuthenticationPrincipal CdciPrincipal actor) {
		pipelines.delete(projectId, pipelineId, actor);
	}
}
