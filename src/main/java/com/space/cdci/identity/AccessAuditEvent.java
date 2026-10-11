package com.space.cdci.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_audit_events")
public class AccessAuditEvent {
	@Id
	private UUID id;

	@Column(name = "actor_email", nullable = false, length = 254)
	private String actorEmail;

	@Column(nullable = false, length = 64)
	private String action;

	@Column(name = "target_type", nullable = false, length = 48)
	private String targetType;

	@Column(name = "target_id", nullable = false, length = 64)
	private String targetId;

	@Column(nullable = false, length = 500)
	private String summary;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AccessAuditEvent() {}

	public AccessAuditEvent(String actorEmail, String action, String targetType, String targetId, String summary) {
		this.id = UUID.randomUUID();
		this.actorEmail = actorEmail;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.summary = summary;
		this.createdAt = Instant.now();
	}

	public UUID getId() { return id; }
	public String getActorEmail() { return actorEmail; }
	public String getAction() { return action; }
	public String getTargetType() { return targetType; }
	public String getTargetId() { return targetId; }
	public String getSummary() { return summary; }
	public Instant getCreatedAt() { return createdAt; }
}
