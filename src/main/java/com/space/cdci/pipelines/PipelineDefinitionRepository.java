package com.space.cdci.pipelines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineDefinitionRepository extends JpaRepository<PipelineDefinition, UUID> {
	List<PipelineDefinition> findAllByProject_IdOrderByUpdatedAtDesc(UUID projectId);
	long countByProject_IdIn(List<UUID> projectIds);
	Optional<PipelineDefinition> findByIdAndProject_Id(UUID id, UUID projectId);
	long countByRepository_Id(UUID repositoryId);
}
