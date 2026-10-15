package com.space.cdci.identity;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class AccessAuditService {
	private final AccessAuditRepository events;

	private final int retentionDays;

	public AccessAuditService(AccessAuditRepository events, @Value("${cdci.audit.retention-days:365}") int retentionDays) {
		this.events = events;
		this.retentionDays = Math.max(30, Math.min(retentionDays, 3650));
	}

	@Transactional
	public void record(String actor, String action, String targetType, String targetId, String summary) {
		events.save(new AccessAuditEvent(actor, action, targetType, targetId, summary));
	}

	@Transactional
	public void recordProject(UUID projectId, String actor, String action, String targetType, String targetId, String summary) {
		events.save(new AccessAuditEvent(projectId, actor, action, targetType, targetId, summary));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordProjectObservation(UUID projectId, String actor, String action, String targetType, String targetId, String summary) {
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

	@Scheduled(fixedDelayString = "${cdci.audit.retention-interval-ms:86400000}")
	@Transactional
	public void purgeExpired() {
		events.purgeBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
	}
}
