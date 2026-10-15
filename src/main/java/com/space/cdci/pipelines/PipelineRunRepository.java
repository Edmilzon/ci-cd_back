package com.space.cdci.pipelines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, UUID> {
	List<PipelineRun> findTop100ByPipeline_IdOrderByRequestedAtDesc(UUID pipelineId);
	Optional<PipelineRun> findByIdAndPipeline_Project_Id(UUID runId, UUID projectId);
	boolean existsByPipeline_Id(UUID pipelineId);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	java.util.Optional<PipelineRun> findFirstByStatusOrderByRequestedAtAsc(PipelineRunStatus status);
}
