package com.space.cdci.pipelines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, UUID> {
	List<PipelineVersion> findAllByPipeline_IdOrderByVersionNumberDesc(UUID pipelineId);
	Optional<PipelineVersion> findByPipeline_IdAndVersionNumber(UUID pipelineId, int versionNumber);
}
