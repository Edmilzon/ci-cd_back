package com.space.cdci.projects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
	boolean existsBySlugIgnoreCase(String slug);
	Optional<Project> findByIdAndArchivedFalse(UUID id);
	List<Project> findByArchivedFalseOrderByUpdatedAtDesc();
}
