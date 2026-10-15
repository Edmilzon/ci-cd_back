package com.space.cdci.pipelines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PipelineDeploymentRepository extends JpaRepository<PipelineDeployment, UUID> {
	@EntityGraph(attributePaths = {"pipelineRun", "pipelineRun.pipeline", "pipelineRun.pipelineVersion", "pipelineRun.requestedBy", "requestedBy", "approvedBy"})
	List<PipelineDeployment> findTop100ByProject_IdOrderByRequestedAtDesc(UUID projectId);
	@EntityGraph(attributePaths = {"pipelineRun", "pipelineRun.pipeline", "pipelineRun.pipelineVersion", "pipelineRun.requestedBy", "requestedBy", "approvedBy"})
	List<PipelineDeployment> findTop20ByProject_IdInOrderByRequestedAtDesc(List<UUID> projectIds);
	@EntityGraph(attributePaths = {"pipelineRun", "pipelineRun.pipeline", "pipelineRun.pipelineVersion", "pipelineRun.requestedBy", "requestedBy", "approvedBy"})
	Optional<PipelineDeployment> findByIdAndProject_Id(UUID id, UUID projectId);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = {"pipelineRun", "pipelineRun.pipeline", "pipelineRun.pipelineVersion", "pipelineRun.requestedBy", "requestedBy", "approvedBy"})
	@Query("select deployment from PipelineDeployment deployment where deployment.id = :id and deployment.project.id = :projectId")
	Optional<PipelineDeployment> findForUpdate(@Param("id") UUID id, @Param("projectId") UUID projectId);
	Optional<PipelineDeployment> findByPipelineRun_IdAndJobId(UUID runId, String jobId);
	@EntityGraph(attributePaths = {"pipelineRun", "pipelineRun.pipeline", "pipelineRun.pipelineVersion", "pipelineRun.requestedBy", "requestedBy", "approvedBy"})
	List<PipelineDeployment> findAllByPipelineRun_IdAndStatus(UUID runId, PipelineDeploymentStatus status);
	@EntityGraph(attributePaths = {"pipelineRun", "pipelineRun.pipeline", "pipelineRun.pipelineVersion", "pipelineRun.requestedBy", "requestedBy", "approvedBy"})
	List<PipelineDeployment> findAllByPipelineRun_IdAndStatusIn(UUID runId, List<PipelineDeploymentStatus> statuses);
	@Query("select case when count(deployment) > 0 then true else false end from PipelineDeployment deployment " +
			"where deployment.pipelineRun.pipeline.repository.githubRepositoryId = :repositoryId " +
			"and lower(deployment.environmentName) = lower(:environmentName) " +
			"and deployment.status = :status and deployment.id <> :deploymentId")
	boolean existsByRepositoryEnvironmentStatus(@Param("repositoryId") long repositoryId,
			@Param("environmentName") String environmentName, @Param("status") PipelineDeploymentStatus status,
			@Param("deploymentId") UUID deploymentId);
	@Modifying
	@Query(value = "insert into pipeline_deployment_locks (github_repository_id, environment_name) " +
			"values (:repositoryId, lower(:environmentName)) on conflict do nothing", nativeQuery = true)
	int ensureEnvironmentLock(@Param("repositoryId") long repositoryId, @Param("environmentName") String environmentName);
	@Query(value = "select github_repository_id from pipeline_deployment_locks " +
			"where github_repository_id = :repositoryId and environment_name = lower(:environmentName) for update", nativeQuery = true)
	Long lockEnvironment(@Param("repositoryId") long repositoryId, @Param("environmentName") String environmentName);
}
