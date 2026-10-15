package com.space.cdci.identity;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessAuditService {
	private final AccessAuditRepository events;

	public AccessAuditService(AccessAuditRepository events) { this.events = events; }

	@Transactional
	public void record(String actor, String action, String targetType, String targetId, String summary) {
		events.save(new AccessAuditEvent(actor, action, targetType, targetId, summary));
	}

	@Transactional
	public void recordProject(UUID projectId, String actor, String action, String targetType, String targetId, String summary) {
		events.save(new AccessAuditEvent(projectId, actor, action, targetType, targetId, summary));
	}

	@Transactional(readOnly = true)
	public Page<AccessAuditEvent> page(Pageable pageable) { return events.findAll(pageable); }

	@Transactional(readOnly = true)
	public Page<AccessAuditEvent> pageProject(UUID projectId, Collection<String> targetTypes, Pageable pageable) {
		return targetTypes == null ? events.findByProjectId(projectId, pageable)
				: events.findByProjectIdAndTargetTypeIn(projectId, targetTypes, pageable);
	}

	@Transactional(readOnly = true)
	public List<AccessAuditEvent> recentForProjects(Collection<UUID> projectIds) {
		return projectIds.isEmpty() ? List.of() : events.findTop50ByProjectIdInOrderByCreatedAtDescIdDesc(projectIds);
	}
}
