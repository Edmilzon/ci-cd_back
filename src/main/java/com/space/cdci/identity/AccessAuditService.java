package com.space.cdci.identity;

import java.util.UUID;
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

	@Transactional(readOnly = true)
	public Page<AccessAuditEvent> page(Pageable pageable) { return events.findAll(pageable); }
}
