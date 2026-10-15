package com.space.cdci.identity;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessAuditRepository extends JpaRepository<AccessAuditEvent, UUID> {
	Page<AccessAuditEvent> findByProjectId(UUID projectId, Pageable pageable);
	Page<AccessAuditEvent> findByProjectIdAndTargetTypeIn(UUID projectId, Collection<String> targetTypes, Pageable pageable);
	List<AccessAuditEvent> findTop50ByProjectIdInOrderByCreatedAtDescIdDesc(Collection<UUID> projectIds);
}
