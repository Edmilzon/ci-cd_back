package com.space.cdci.projects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {
	Optional<ProjectMember> findByProject_IdAndUser_Id(UUID projectId, UUID userId);
	boolean existsByProject_IdAndUser_Id(UUID projectId, UUID userId);
	List<ProjectMember> findAllByProject_IdOrderByCreatedAtAsc(UUID projectId);
	long countByProject_Id(UUID projectId);
	long countByProject_IdAndAccessLevel(UUID projectId, ProjectAccessLevel accessLevel);
	@Query("select distinct m.project from ProjectMember m where m.user.id = :userId and m.project.archived = false order by m.project.updatedAt desc")
	List<Project> findActiveProjectsByUserId(@Param("userId") UUID userId);
}
