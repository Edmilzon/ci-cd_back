package com.space.cdci.projects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectServerRepository extends JpaRepository<ProjectServer, UUID> {
	@EntityGraph(attributePaths = {"createdBy", "updatedBy"})
	List<ProjectServer> findAllByProject_IdOrderByNameAsc(UUID projectId);

	@EntityGraph(attributePaths = {"createdBy", "updatedBy"})
	Optional<ProjectServer> findByIdAndProject_Id(UUID id, UUID projectId);

	boolean existsByProject_IdAndNameIgnoreCase(UUID projectId, String name);

	boolean existsByProject_IdAndNameIgnoreCaseAndIdNot(UUID projectId, String name, UUID id);
}
