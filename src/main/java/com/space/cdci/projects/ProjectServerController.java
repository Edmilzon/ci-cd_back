package com.space.cdci.projects;

import com.space.cdci.identity.CdciPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/servers")
public class ProjectServerController {
	private final ProjectServerService servers;

	public ProjectServerController(ProjectServerService servers) { this.servers = servers; }

	@GetMapping
	public List<ProjectServerDtos.ServerSummary> list(@PathVariable UUID projectId, @AuthenticationPrincipal CdciPrincipal actor) {
		return servers.list(projectId, actor);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectServerDtos.ServerSummary create(@PathVariable UUID projectId,
			@Valid @RequestBody ProjectServerDtos.CreateServerRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return servers.create(projectId, request, actor);
	}

	@PutMapping("/{serverId}")
	public ProjectServerDtos.ServerSummary update(@PathVariable UUID projectId, @PathVariable UUID serverId,
			@Valid @RequestBody ProjectServerDtos.UpdateServerRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return servers.update(projectId, serverId, request, actor);
	}

	@DeleteMapping("/{serverId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID projectId, @PathVariable UUID serverId, @AuthenticationPrincipal CdciPrincipal actor) {
		servers.delete(projectId, serverId, actor);
	}

	@PostMapping("/{serverId}/test")
	public ProjectServerDtos.TestConnectionResponse test(@PathVariable UUID projectId, @PathVariable UUID serverId,
			@AuthenticationPrincipal CdciPrincipal actor) {
		return servers.testConnection(projectId, serverId, actor);
	}

	@PostMapping("/{serverId}/commands")
	public ProjectServerDtos.ExecuteCommandResponse execute(@PathVariable UUID projectId, @PathVariable UUID serverId,
			@Valid @RequestBody ProjectServerDtos.ExecuteCommandRequest request, @AuthenticationPrincipal CdciPrincipal actor) {
		return servers.executeCommand(projectId, serverId, request, actor);
	}
}
