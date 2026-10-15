package com.space.cdci.identity;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessAuditRepository extends JpaRepository<AccessAuditEvent, UUID> {
	Page<AccessAuditEvent> findByProjectId(UUID projectId, Pageable pageable);
	Page<AccessAuditEvent> findByProjectIdAndTargetTypeIn(UUID projectId, Collection<String> targetTypes, Pageable pageable);
	List<AccessAuditEvent> findTop50ByProjectIdInOrderByCreatedAtDescIdDesc(Collection<UUID> projectIds);
	@Modifying
	@Query("delete from AccessAuditEvent event where event.createdAt < :before")
	int purgeBefore(@Param("before") Instant before);
}
