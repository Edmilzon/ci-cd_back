package com.space.cdci.projects;

import com.space.cdci.identity.AccessAuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class ProjectActivityDtos {
	private ProjectActivityDtos() {}

	public record ActivitySummary(UUID id, UUID projectId, String actorEmail, String action, String category,
			String targetType, String targetId, String summary, Instant createdAt) {
		public static ActivitySummary from(AccessAuditEvent event) {
			return new ActivitySummary(event.getId(), event.getProjectId(), event.getActorEmail(), event.getAction(),
				categoryForTargetType(event.getTargetType()), event.getTargetType(), event.getTargetId(), event.getSummary(), event.getCreatedAt());
		}
	}

	public record PageResponse(List<ActivitySummary> content, int page, int size, long totalElements, int totalPages) {
		public static PageResponse from(Page<AccessAuditEvent> page) {
			return new PageResponse(page.getContent().stream().map(ActivitySummary::from).toList(), page.getNumber(),
				page.getSize(), page.getTotalElements(), page.getTotalPages());
		}
	}

	public static String categoryForTargetType(String targetType) {
		return switch (targetType) {
			case "PROJECT_MEMBER" -> "ACCESS";
			case "PIPELINE", "PIPELINE_RUN" -> "PIPELINE";
			case "PIPELINE_DEPLOYMENT" -> "DEPLOYMENT";
			case "PROJECT_SECRET" -> "SECRET";
			default -> "PROJECT";
		};
	}
}
