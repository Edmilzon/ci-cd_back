package com.space.cdci.pipelines;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import com.space.cdci.integrations.github.GitHubIntegrationService;
import com.space.cdci.projects.Project;
import com.space.cdci.projects.ProjectAccessService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineRunService {
	private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/-]{1,255}");
	private final PipelineDefinitionRepository pipelines;
	private final PipelineVersionRepository versions;
	private final PipelineRunRepository runs;
	private final PipelineRunLogRepository logs;
	private final UserRepository users;
	private final ProjectAccessService access;
	private final AccessAuditService audit;
	private final GitHubIntegrationService github;
	private final java.util.regex.Pattern credentialPattern = java.util.regex.Pattern.compile("(?i)(ghu_[a-z0-9_]{20,}|github_pat_[a-z0-9_]{20,}|gh[pousr]_[a-z0-9]{20,})");

	public PipelineRunService(PipelineDefinitionRepository pipelines, PipelineVersionRepository versions, PipelineRunRepository runs,
			PipelineRunLogRepository logs, UserRepository users, ProjectAccessService access, AccessAuditService audit,
			GitHubIntegrationService github) {
		this.pipelines = pipelines;
		this.versions = versions;
		this.runs = runs;
		this.logs = logs;
		this.users = users;
		this.access = access;
		this.audit = audit;
		this.github = github;
	}

	@Transactional
	public PipelineDtos.RunSummary queue(UUID projectId, UUID pipelineId, @Valid PipelineDtos.QueueRunRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		PipelineDefinition pipeline = requirePipeline(projectId, pipelineId);
		PipelineVersion version = versions.findByPipeline_IdAndVersionNumber(pipelineId, pipeline.getCurrentVersion()).orElseThrow(PipelineRunService::notFound);
		String branch = request.branchName() == null || request.branchName().isBlank()
				? pipeline.getRepository().getDefaultBranch() : request.branchName().trim();
		if (!isSafeRef(branch)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La rama indicada no es válida.");
		PipelineRun run = runs.save(new PipelineRun(pipeline, version, branch, user(actor.id())));
		logs.save(new PipelineRunLog(run, "SYSTEM", "Ejecución manual encolada; esperando un worker aislado."));
		project.touch();
		audit.record(actor.email(), "PIPELINE_RUN_QUEUED", "PIPELINE_RUN", run.getId().toString(), "Encoló la ejecución manual de " + pipeline.getName() + " (v" + version.getVersionNumber() + ").");
		return summary(run);
	}

	@Transactional(readOnly = true)
	public List<PipelineDtos.RunSummary> list(UUID projectId, UUID pipelineId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		requirePipeline(projectId, pipelineId);
		return runs.findTop100ByPipeline_IdOrderByRequestedAtDesc(pipelineId).stream().map(PipelineRunService::summary).toList();
	}

	@Transactional(readOnly = true)
	public PipelineDtos.RunSummary get(UUID projectId, UUID runId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		return summary(requireRun(projectId, runId));
	}

	@Transactional(readOnly = true)
	public List<PipelineDtos.RunLog> logs(UUID projectId, UUID runId, long afterId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		if (afterId < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cursor de logs no puede ser negativo.");
		requireRun(projectId, runId);
		return logs.findTop250ByRun_IdAndIdGreaterThanOrderByIdAsc(runId, afterId).stream()
				.map(log -> new PipelineDtos.RunLog(log.getId(), log.getStream(), log.getMessage(), log.getCreatedAt())).toList();
	}

	@Transactional
	public PipelineDtos.WorkerClaim claim(String workerId) {
		PipelineRun run = runs.findFirstByStatusOrderByRequestedAtAsc(PipelineRunStatus.QUEUED).orElse(null);
		if (run == null) return null;
		var repository = run.getPipeline().getRepository();
		var sourceAccess = github.runnerAccess(repository.getId());
		run.claim();
		logs.save(new PipelineRunLog(run, "SYSTEM", "Worker " + safeWorkerId(workerId) + " inició la ejecución."));
		return new PipelineDtos.WorkerClaim(run.getId(), run.getPipeline().getId(), run.getPipeline().getName(),
			run.getPipelineVersion().getVersionNumber(), run.getBranchName(), sourceAccess.fullName(), sourceAccess.cloneUrl(),
			sourceAccess.accessToken(), run.getPipelineVersion().getYamlDefinition(), run.getStartedAt());
	}

	@Transactional
	public void appendWorkerLog(UUID runId, PipelineDtos.WorkerLogRequest request) {
		PipelineRun run = runs.findById(runId).orElseThrow(PipelineRunService::notFound);
		if (run.getStatus() != PipelineRunStatus.RUNNING) throw new ResponseStatusException(HttpStatus.CONFLICT, "La ejecución no está activa.");
		if (logs.countByRun_Id(runId) >= 2000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Se alcanzó el límite de logs para esta ejecución.");
		logs.save(new PipelineRunLog(run, request.stream(), redact(request.message().trim())));
	}

	@Transactional
	public PipelineDtos.RunSummary completeFromWorker(UUID runId, PipelineDtos.WorkerCompleteRequest request) {
		PipelineRun run = runs.findById(runId).orElseThrow(PipelineRunService::notFound);
		if (run.getStatus() != PipelineRunStatus.RUNNING) throw new ResponseStatusException(HttpStatus.CONFLICT, "La ejecución no está activa.");
		boolean succeeded = "SUCCEEDED".equals(request.status());
		String failure = succeeded ? null : redact(request.failureMessage() == null || request.failureMessage().isBlank() ? "El worker reportó una falla." : request.failureMessage().trim());
		run.complete(succeeded, failure);
		logs.save(new PipelineRunLog(run, "SYSTEM", succeeded ? "El worker finalizó la ejecución correctamente." : "El worker marcó la ejecución como fallida."));
		return summary(run);
	}

	private PipelineDefinition requirePipeline(UUID projectId, UUID pipelineId) {
		return pipelines.findByIdAndProject_Id(pipelineId, projectId).orElseThrow(PipelineRunService::notFound);
	}
	private PipelineRun requireRun(UUID projectId, UUID runId) {
		return runs.findByIdAndPipeline_Project_Id(runId, projectId).orElseThrow(PipelineRunService::notFound);
	}
	private UserAccount user(UUID id) { return users.findById(id).orElseThrow(PipelineRunService::notFound); }
	private static PipelineDtos.RunSummary summary(PipelineRun run) {
		return new PipelineDtos.RunSummary(run.getId(), run.getPipeline().getId(), run.getPipeline().getName(), run.getPipelineVersion().getVersionNumber(),
			run.getBranchName(), run.getTriggerType(), run.getStatus().name(), run.getRequestedBy().getEmail(), run.getRequestedAt(),
			run.getStartedAt(), run.getCompletedAt(), run.getFailureMessage());
	}
	private static boolean isSafeRef(String ref) {
		if (!SAFE_REF.matcher(ref).matches() || ref.startsWith("/") || ref.endsWith("/") || ref.endsWith(".") || ref.contains("..") || ref.contains("//") || ref.contains("@{")) return false;
		for (String component : ref.split("/")) if (component.endsWith(".lock")) return false;
		return true;
	}
	private String redact(String message) { return credentialPattern.matcher(message).replaceAll("[credencial ocultada]"); }
	private static String safeWorkerId(String workerId) { return workerId.replaceAll("[^A-Za-z0-9._-]", "_"); }
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el recurso del proyecto."); }
}
