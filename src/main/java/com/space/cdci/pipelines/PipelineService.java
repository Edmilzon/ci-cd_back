package com.space.cdci.pipelines;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import com.space.cdci.projects.LinkedRepository;
import com.space.cdci.projects.LinkedRepositoryRepository;
import com.space.cdci.projects.Project;
import com.space.cdci.projects.ProjectAccessService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PipelineService {
	private final PipelineDefinitionRepository pipelines;
	private final PipelineVersionRepository versions;
	private final LinkedRepositoryRepository repositories;
	private final UserRepository users;
	private final ProjectAccessService access;
	private final PipelineYamlValidator validator;
	private final AccessAuditService audit;

	public PipelineService(PipelineDefinitionRepository pipelines, PipelineVersionRepository versions,
			LinkedRepositoryRepository repositories, UserRepository users, ProjectAccessService access,
			PipelineYamlValidator validator, AccessAuditService audit) {
		this.pipelines = pipelines;
		this.versions = versions;
		this.repositories = repositories;
		this.users = users;
		this.access = access;
		this.validator = validator;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<PipelineDtos.PipelineSummary> list(UUID projectId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		return pipelines.findAllByProject_IdOrderByUpdatedAtDesc(projectId).stream().map(PipelineService::summary).toList();
	}

	@Transactional(readOnly = true)
	public PipelineDtos.ValidationResult validate(UUID projectId, PipelineDtos.ValidateRequest request, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		return validator.validate(request.yaml());
	}

	@Transactional
	public PipelineDtos.PipelineSummary create(UUID projectId, @Valid PipelineDtos.CreatePipelineRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		PipelineDtos.ValidationResult result = requireValid(request.yaml());
		LinkedRepository repository = repositories.findByIdAndProject_Id(request.repositoryId(), projectId)
				.orElseThrow(PipelineService::notFound);
		PipelineDefinition pipeline = pipelines.save(new PipelineDefinition(project, repository, result.name().trim(), user(actor.id())));
		versions.save(new PipelineVersion(pipeline, 1, request.yaml(), sha256(request.yaml()), user(actor.id())));
		project.touch();
		audit.record(actor.email(), "PIPELINE_CREATED", "PIPELINE", pipeline.getId().toString(), "Creó el pipeline " + pipeline.getName() + " en " + repository.getFullName() + ".");
		return summary(pipeline);
	}

	@Transactional
	public PipelineDtos.VersionSummary addVersion(UUID projectId, UUID pipelineId, @Valid PipelineDtos.AddVersionRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		PipelineDefinition pipeline = requirePipeline(projectId, pipelineId);
		PipelineDtos.ValidationResult result = requireValid(request.yaml());
		int number = pipeline.nextVersion(result.name().trim());
		PipelineVersion version = versions.save(new PipelineVersion(pipeline, number, request.yaml(), sha256(request.yaml()), user(actor.id())));
		project.touch();
		audit.record(actor.email(), "PIPELINE_VERSION_CREATED", "PIPELINE", pipeline.getId().toString(), "Guardó la versión " + number + " del pipeline " + pipeline.getName() + ".");
		return versionSummary(version);
	}

	@Transactional(readOnly = true)
	public List<PipelineDtos.VersionSummary> listVersions(UUID projectId, UUID pipelineId, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		PipelineDefinition pipeline = requirePipeline(projectId, pipelineId);
		return versions.findAllByPipeline_IdOrderByVersionNumberDesc(pipeline.getId()).stream().map(PipelineService::versionSummary).toList();
	}

	@Transactional(readOnly = true)
	public PipelineDtos.VersionDetail getVersion(UUID projectId, UUID pipelineId, int number, CdciPrincipal actor) {
		access.requireRead(projectId, actor);
		PipelineDefinition pipeline = requirePipeline(projectId, pipelineId);
		PipelineVersion version = versions.findByPipeline_IdAndVersionNumber(pipeline.getId(), number).orElseThrow(PipelineService::notFound);
		return new PipelineDtos.VersionDetail(version.getVersionNumber(), version.getSha256(), version.getCreatedBy().getEmail(),
			version.getCreatedAt(), version.getYamlDefinition());
	}

	@Transactional
	public void delete(UUID projectId, UUID pipelineId, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		PipelineDefinition pipeline = requirePipeline(projectId, pipelineId);
		String name = pipeline.getName();
		pipelines.delete(pipeline);
		project.touch();
		audit.record(actor.email(), "PIPELINE_DELETED", "PIPELINE", pipelineId.toString(), "Eliminó el pipeline " + name + " y sus versiones.");
	}

	private PipelineDtos.ValidationResult requireValid(String yaml) {
		PipelineDtos.ValidationResult result = validator.validate(yaml);
		if (!result.valid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", result.errors()));
		return result;
	}

	private PipelineDefinition requirePipeline(UUID projectId, UUID pipelineId) {
		return pipelines.findByIdAndProject_Id(pipelineId, projectId).orElseThrow(PipelineService::notFound);
	}

	private UserAccount user(UUID id) { return users.findById(id).orElseThrow(PipelineService::notFound); }
	private static PipelineDtos.PipelineSummary summary(PipelineDefinition pipeline) {
		return new PipelineDtos.PipelineSummary(pipeline.getId(), pipeline.getRepository().getId(), pipeline.getRepository().getFullName(),
			pipeline.getName(), pipeline.getCurrentVersion(), pipeline.getCreatedAt(), pipeline.getUpdatedAt());
	}
	private static PipelineDtos.VersionSummary versionSummary(PipelineVersion version) {
		return new PipelineDtos.VersionSummary(version.getVersionNumber(), version.getSha256(), version.getCreatedBy().getEmail(), version.getCreatedAt());
	}
	private static String sha256(String yaml) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 no está disponible.", impossible); }
	}
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el recurso del proyecto."); }
}
