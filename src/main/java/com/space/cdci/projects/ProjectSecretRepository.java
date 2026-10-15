package com.space.cdci.projects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectSecretRepository extends JpaRepository<ProjectSecret, UUID> {
	List<ProjectSecret> findAllByProject_IdOrderByNameAsc(UUID projectId);
	Optional<ProjectSecret> findByProject_IdAndName(UUID projectId, String name);
	List<ProjectSecret> findAllByProject_IdAndNameIn(UUID projectId, List<String> names);
}
