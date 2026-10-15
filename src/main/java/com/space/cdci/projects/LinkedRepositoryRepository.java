package com.space.cdci.projects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkedRepositoryRepository extends JpaRepository<LinkedRepository, UUID> {
	List<LinkedRepository> findAllByProject_IdOrderByCreatedAtDesc(UUID projectId);
	Optional<LinkedRepository> findByIdAndProject_Id(UUID id, UUID projectId);
	long countByProject_Id(UUID projectId);
	boolean existsByProject_IdAndGithubRepositoryId(UUID projectId, long githubRepositoryId);
	boolean existsByGithubConnection_Id(UUID connectionId);
}
