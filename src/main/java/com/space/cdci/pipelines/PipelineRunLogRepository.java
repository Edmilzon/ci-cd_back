package com.space.cdci.pipelines;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineRunLogRepository extends JpaRepository<PipelineRunLog, Long> {
	List<PipelineRunLog> findTop250ByRun_IdAndIdGreaterThanOrderByIdAsc(java.util.UUID runId, long afterId);
	long countByRun_Id(java.util.UUID runId);
}
