package com.space.cdci.projects;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectActivityService {
	private final ProjectAccessService access;
	private final AccessAuditService audit;

	public ProjectActivityService(ProjectAccessService access, AccessAuditService audit) {
		this.access = access;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public ProjectActivityDtos.PageResponse page(UUID projectId, String requestedCategory, int page, int size, CdciPrincipal actor) {
		access.requireActivityRead(projectId, actor);
		List<String> targetTypes = targetTypes(requestedCategory);
		PageRequest pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
			Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
		Page<com.space.cdci.identity.AccessAuditEvent> result = audit.pageProject(projectId, targetTypes, pageable);
		return ProjectActivityDtos.PageResponse.from(result);
	}

	private static List<String> targetTypes(String requestedCategory) {
		String category = requestedCategory == null ? "ALL" : requestedCategory.trim().toUpperCase(Locale.ROOT);
		return switch (category) {
			case "ALL" -> null;
			case "PROJECT" -> List.of("PROJECT", "PROJECT_REPOSITORY");
			case "ACCESS" -> List.of("PROJECT_MEMBER");
			case "PIPELINE" -> List.of("PIPELINE", "PIPELINE_RUN");
			case "DEPLOYMENT" -> List.of("PIPELINE_DEPLOYMENT");
			case "SECRET" -> List.of("PROJECT_SECRET");
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La categoría de actividad no es válida.");
		};
	}
}
