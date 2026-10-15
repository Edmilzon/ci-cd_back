package com.space.cdci.pipelines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {
	List<PipelineRun> findTop100ByPipeline_IdOrderByRequestedAtDesc(UUID pipelineId);
	@EntityGraph(attributePaths = {"pipeline", "pipeline.project", "pipeline.repository", "requestedBy", "pipelineVersion"})
	List<PipelineRun> findTop50ByPipeline_Project_IdInOrderByRequestedAtDesc(List<UUID> projectIds);
	long countByPipeline_Project_IdInAndRequestedAtGreaterThanEqual(List<UUID> projectIds, java.time.Instant from);
	long countByPipeline_Project_IdInAndStatusInAndRequestedAtGreaterThanEqual(List<UUID> projectIds,
			List<PipelineRunStatus> statuses, java.time.Instant from);
	long countByPipeline_Project_IdInAndStatusAndRequestedAtGreaterThanEqual(List<UUID> projectIds,
			PipelineRunStatus status, java.time.Instant from);
	long countByPipeline_Project_IdInAndStatusIn(List<UUID> projectIds, List<PipelineRunStatus> statuses);
	@Query(value = """
		select (r.requested_at at time zone 'America/La_Paz')::date as run_day,
		       count(*) filter (where r.status = 'SUCCEEDED') as succeeded,
		       count(*) filter (where r.status = 'FAILED') as failed,
		       count(*) filter (where r.status in ('QUEUED', 'RUNNING')) as active
		from pipeline_runs r
		join pipelines p on p.id = r.pipeline_id
		where p.project_id in (:projectIds) and r.requested_at >= :from
		group by run_day order by run_day
		""", nativeQuery = true)
	List<Object[]> dashboardDailyCounts(@Param("projectIds") List<UUID> projectIds, @Param("from") java.time.Instant from);
	@Query(value = """
		select percentile_cont(0.5) within group (order by extract(epoch from (r.completed_at - r.started_at)) * 1000)
		from pipeline_runs r
		join pipelines p on p.id = r.pipeline_id
		where p.project_id in (:projectIds) and r.requested_at >= :from
		  and r.status in ('SUCCEEDED', 'FAILED') and r.started_at is not null and r.completed_at is not null
		""", nativeQuery = true)
	Double dashboardMedianDurationMillis(@Param("projectIds") List<UUID> projectIds, @Param("from") java.time.Instant from);
	Optional<PipelineRun> findByIdAndPipeline_Project_Id(UUID runId, UUID projectId);
	boolean existsByPipeline_Id(UUID pipelineId);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	java.util.Optional<PipelineRun> findFirstByStatusOrderByRequestedAtAsc(PipelineRunStatus status);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<PipelineRun> findAllByStatusAndLeaseExpiresAtBefore(PipelineRunStatus status, java.time.Instant before);
	@Modifying
	@Query("delete from PipelineRun run where run.status in :statuses and run.requestedAt < :before")
	int deleteExpiredRuns(@Param("statuses") List<PipelineRunStatus> statuses, @Param("before") java.time.Instant before);
}
