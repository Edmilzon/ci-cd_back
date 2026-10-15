package com.space.cdci.pipelines;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import com.space.cdci.integrations.github.GitHubIntegrationService;
import com.space.cdci.projects.ProjectAccessService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineDeploymentService {
	private final PipelineDeploymentRepository deployments;
	private final PipelineRunRepository runs;
	private final PipelineRunLogRepository logs;
	private final PipelineDefinitionRepository pipelines;
	private final ProjectAccessService access;
	private final UserRepository users;
	private final GitHubIntegrationService github;
	private final AccessAuditService audit;

	public PipelineDeploymentService(PipelineDeploymentRepository deployments, PipelineRunRepository runs,
			PipelineRunLogRepository logs, PipelineDefinitionRepository pipelines, ProjectAccessService access,
			UserRepository users, GitHubIntegrationService github, AccessAuditService audit) {
		this.deployments = deployments;
		this.runs = runs;
		this.logs = logs;
		this.pipelines = pipelines;
		this.access = access;
		this.users = users;
		this.github = github;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<PipelineDeploymentDtos.DeploymentSummary> list(UUID projectId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		return deployments.findTop100ByProject_IdOrderByRequestedAtDesc(projectId).stream().map(PipelineDeploymentService::summary).toList();
	}

	@Transactional
	public PipelineDeploymentDtos.DeploymentSummary request(UUID runId, @Valid PipelineDeploymentDtos.WorkerRequestDeployment request) {
		PipelineRun run = workerRun(runId, request.workerId());
		if (run.getSourceCommitSha() == null || !run.getSourceCommitSha().equals(request.commitSha())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El commit del despliegue no coincide con el checkout fijado por el worker.");
		}
		if (run.getRollbackEnvironment() != null && !run.getRollbackEnvironment().equalsIgnoreCase(request.environmentName())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El rollback solo puede desplegar al entorno de origen.");
		}
		PipelineDeployment deployment = deployments.findByPipelineRun_IdAndJobId(runId, request.jobId()).orElse(null);
		if (deployment == null) {
			deployment = deployments.save(new PipelineDeployment(run, request.jobId(), request.environmentName(), request.commitSha()));
			String message = PipelineDeployment.isProduction(request.environmentName())
					? "El despliegue a production espera aprobación de una persona mantenedora."
					: "El despliegue a " + request.environmentName() + " fue solicitado a GitHub.";
			logs.save(new PipelineRunLog(run, "SYSTEM", message));
		}
		return summary(deployment);
	}

	@Transactional(readOnly = true)
	public PipelineDeploymentDtos.ApprovalStatus approvalStatus(UUID runId, UUID deploymentId, PipelineDeploymentDtos.WorkerAction request) {
		PipelineRun run = workerRun(runId, request.workerId());
		PipelineDeployment deployment = deployments.findByIdAndProject_Id(deploymentId, run.getPipeline().getProject().getId())
				.filter(value -> value.getPipelineRun().getId().equals(runId)).orElseThrow(PipelineDeploymentService::notFound);
		PipelineDeploymentStatus status = deployment.getStatus();
		return new PipelineDeploymentDtos.ApprovalStatus(status != PipelineDeploymentStatus.WAITING_APPROVAL
				&& status != PipelineDeploymentStatus.REJECTED && status != PipelineDeploymentStatus.FAILED,
				status == PipelineDeploymentStatus.REJECTED || status == PipelineDeploymentStatus.FAILED, status.name());
	}

	@Transactional
	public PipelineDeploymentDtos.DeploymentSummary approve(UUID projectId, UUID deploymentId, CdciPrincipal actor) {
		access.requireManage(projectId, actor);
		PipelineDeployment deployment = deployments.findForUpdate(deploymentId, projectId).orElseThrow(PipelineDeploymentService::notFound);
		if (deployment.getRequestedBy().getId().equals(actor.id())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La persona que solicitó el despliegue no puede aprobar su propio despliegue a production.");
		}
		UserAccount approver = users.findById(actor.id()).orElseThrow(PipelineDeploymentService::notFound);
		try { deployment.approve(approver); }
		catch (IllegalStateException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "El despliegue ya no espera aprobación."); }
		logs.save(new PipelineRunLog(deployment.getPipelineRun(), "SYSTEM", "" + actor.email() + " aprobó el despliegue a " + deployment.getEnvironmentName() + "."));
		audit.record(actor.email(), "PIPELINE_DEPLOYMENT_APPROVED", "PIPELINE_DEPLOYMENT", deploymentId.toString(),
				"Aprobó el despliegue a " + deployment.getEnvironmentName() + " para " + deployment.getPipelineRun().getPipeline().getName() + ".");
		return summary(deployment);
	}

	@Transactional
	public PipelineDeploymentDtos.DeploymentSummary reject(UUID projectId, UUID deploymentId, CdciPrincipal actor) {
		access.requireManage(projectId, actor);
		PipelineDeployment deployment = deployments.findForUpdate(deploymentId, projectId).orElseThrow(PipelineDeploymentService::notFound);
		UserAccount reviewer = users.findById(actor.id()).orElseThrow(PipelineDeploymentService::notFound);
		if (deployment.getRequestedBy().getId().equals(actor.id())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La persona que solicitó el despliegue no puede rechazar su propio despliegue a production.");
		}
		try { deployment.reject(reviewer); }
		catch (IllegalStateException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "El despliegue ya no espera aprobación."); }
		logs.save(new PipelineRunLog(deployment.getPipelineRun(), "SYSTEM", actor.email() + " rechazó el despliegue a " + deployment.getEnvironmentName() + "."));
		audit.record(actor.email(), "PIPELINE_DEPLOYMENT_REJECTED", "PIPELINE_DEPLOYMENT", deploymentId.toString(),
				"Rechazó el despliegue a " + deployment.getEnvironmentName() + " para " + deployment.getPipelineRun().getPipeline().getName() + ".");
		return summary(deployment);
	}

	@Transactional
	public PipelineDeploymentDtos.DeploymentSummary start(UUID runId, UUID deploymentId, PipelineDeploymentDtos.WorkerAction request) {
		PipelineRun run = workerRun(runId, request.workerId());
		PipelineDeployment deployment = deploymentForWorker(run, deploymentId);
		if (deployment.getStatus() == PipelineDeploymentStatus.IN_PROGRESS) return summary(deployment);
		if (deployment.getStatus() != PipelineDeploymentStatus.APPROVED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El despliegue no cuenta con aprobación.");
		}
		long githubRepositoryId = run.getPipeline().getRepository().getGithubRepositoryId();
		deployments.ensureEnvironmentLock(githubRepositoryId, deployment.getEnvironmentName());
		deployments.lockEnvironment(githubRepositoryId, deployment.getEnvironmentName());
		if (deployments.existsByRepositoryEnvironmentStatus(githubRepositoryId, deployment.getEnvironmentName(),
				PipelineDeploymentStatus.IN_PROGRESS, deploymentId)) {
			return summary(deployment);
		}
		String description = "CDCI " + run.getPipeline().getName() + " · " + deployment.getEnvironmentName() + " · " + run.getId();
		var created = github.createDeployment(run.getPipeline().getRepository().getId(), run.getId().toString(),
				deployment.getSourceCommitSha(), deployment.getEnvironmentName(), description);
		deployment.started(created.id(), created.htmlUrl());
		try {
			github.updateDeploymentStatus(run.getPipeline().getRepository().getId(), created.id(), deployment.getEnvironmentName(),
					"in_progress", "CDCI está ejecutando el comando de despliegue.", githubLogUrl(deployment));
		} catch (RuntimeException exception) {
			deployment.fail();
			logs.save(new PipelineRunLog(run, "SYSTEM", "No se pudo sincronizar el estado inicial del despliegue con GitHub."));
			return summary(deployment);
		}
		logs.save(new PipelineRunLog(run, "SYSTEM", "GitHub registró el despliegue a " + deployment.getEnvironmentName() + "."));
		return summary(deployment);
	}

	@Transactional
	public PipelineDeploymentDtos.DeploymentSummary complete(UUID runId, UUID deploymentId,
			PipelineDeploymentDtos.WorkerCompleteDeployment request) {
		PipelineRun run = workerRun(runId, request.workerId());
		PipelineDeployment deployment = deploymentForWorker(run, deploymentId);
		if (deployment.getStatus() != PipelineDeploymentStatus.IN_PROGRESS || deployment.getGithubDeploymentId() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El despliegue no está activo para este worker.");
		}
		boolean success = Boolean.TRUE.equals(request.succeeded());
		String state = success ? "success" : "failure";
		String description = success ? "El comando de despliegue terminó correctamente en CDCI." : "El comando de despliegue falló en CDCI.";
		boolean githubStatusSynced = false;
		try {
			github.updateDeploymentStatus(run.getPipeline().getRepository().getId(), deployment.getGithubDeploymentId(),
					deployment.getEnvironmentName(), state, description, githubLogUrl(deployment));
			githubStatusSynced = true;
		} catch (RuntimeException exception) {
			logs.save(new PipelineRunLog(run, "SYSTEM", "No se pudo sincronizar el resultado final del despliegue con GitHub; el estado local conserva el resultado del worker."));
		}
		deployment.complete(success);
		logs.save(new PipelineRunLog(run, "SYSTEM", githubStatusSynced
				? success ? "GitHub marcó el despliegue como exitoso." : "GitHub marcó el despliegue como fallido."
				: success ? "CDCI marcó el despliegue como exitoso." : "CDCI marcó el despliegue como fallido."));
		return summary(deployment);
	}

	@Transactional
	public void failActive(UUID runId) {
		List<PipelineDeployment> active = deployments.findAllByPipelineRun_IdAndStatusIn(runId,
				List.of(PipelineDeploymentStatus.WAITING_APPROVAL, PipelineDeploymentStatus.APPROVED, PipelineDeploymentStatus.IN_PROGRESS));
		for (PipelineDeployment deployment : active) {
			try {
				if (deployment.getStatus() == PipelineDeploymentStatus.IN_PROGRESS && deployment.getGithubDeploymentId() != null) {
				github.updateDeploymentStatus(deployment.getPipelineRun().getPipeline().getRepository().getId(), deployment.getGithubDeploymentId(),
						deployment.getEnvironmentName(), "failure", "La ejecución CDCI se interrumpió antes de finalizar.", githubLogUrl(deployment));
				}
			} catch (RuntimeException ignored) { }
			deployment.fail();
		}
	}

	@Transactional
	public PipelineDtos.RunSummary rollback(UUID projectId, UUID deploymentId, CdciPrincipal actor) {
		access.requireManage(projectId, actor);
		PipelineDeployment source = deployments.findByIdAndProject_Id(deploymentId, projectId).orElseThrow(PipelineDeploymentService::notFound);
		if (source.getStatus() != PipelineDeploymentStatus.SUCCEEDED || source.getSourceCommitSha() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se puede hacer rollback a un despliegue exitoso con commit fijado.");
		}
		PipelineRun previousRun = source.getPipelineRun();
		PipelineDefinition pipeline = previousRun.getPipeline();
		if (!pipelines.existsById(pipeline.getId())) throw notFound();
		UserAccount requester = users.findById(actor.id()).orElseThrow(PipelineDeploymentService::notFound);
		PipelineRun rollback = runs.save(new PipelineRun(pipeline, previousRun.getPipelineVersion(), previousRun.getBranchName(), requester,
				source.getSourceCommitSha(), source.getEnvironmentName(), source.getId()));
		logs.save(new PipelineRunLog(rollback, "SYSTEM", "Rollback manual encolado para " + source.getEnvironmentName() + " al commit " + source.getSourceCommitSha() + "."));
		audit.record(actor.email(), "PIPELINE_DEPLOYMENT_ROLLBACK_QUEUED", "PIPELINE_DEPLOYMENT", source.getId().toString(),
				"Encoló rollback de " + pipeline.getName() + " a " + source.getSourceCommitSha() + " en " + source.getEnvironmentName() + ".");
		return PipelineRunService.summary(rollback);
	}

	private PipelineRun workerRun(UUID runId, String workerId) {
		PipelineRun run = runs.findById(runId).orElseThrow(PipelineDeploymentService::notFound);
		if (run.getStatus() != PipelineRunStatus.RUNNING || !workerId.equals(run.getClaimedByWorkerId())
				|| run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La ejecución no está activa para este worker.");
		}
		return run;
	}

	private PipelineDeployment deploymentForWorker(PipelineRun run, UUID deploymentId) {
		return deployments.findForUpdate(deploymentId, run.getPipeline().getProject().getId())
				.filter(value -> value.getPipelineRun().getId().equals(run.getId())).orElseThrow(PipelineDeploymentService::notFound);
	}

	private static String githubLogUrl(PipelineDeployment deployment) {
		return "https://github.com/" + deployment.getPipelineRun().getPipeline().getRepository().getFullName() + "/deployments";
	}

	private static PipelineDeploymentDtos.DeploymentSummary summary(PipelineDeployment deployment) {
		PipelineRun run = deployment.getPipelineRun();
		return new PipelineDeploymentDtos.DeploymentSummary(deployment.getId(), run.getId(), deployment.getProject().getId(),
			run.getPipeline().getId(), deployment.getProject().getName(), run.getPipeline().getName(),
			run.getPipeline().getRepository().getFullName(), run.getBranchName(), deployment.getEnvironmentName(),
			deployment.getSourceCommitSha(), deployment.getStatus().name(), deployment.getRequestedBy().getEmail(),
			deployment.getApprovedBy() == null ? null : deployment.getApprovedBy().getEmail(), deployment.getRequestedAt(),
			deployment.getApprovedAt(), deployment.getCompletedAt(), deployment.getGithubDeploymentId(), deployment.getGithubHtmlUrl(),
			run.getRollbackOfDeploymentId());
	}

	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el recurso del proyecto."); }
}
