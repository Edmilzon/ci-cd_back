package com.space.cdci.integrations.github;

import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.projects.ProjectDtos;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/api/integrations/github")
public class GitHubIntegrationController {
	private final GitHubIntegrationService github;

	public GitHubIntegrationController(GitHubIntegrationService github) { this.github = github; }

	@PostMapping("/connect")
	public ProjectDtos.GitHubConnectUrl connect(HttpSession session) { return github.beginConnection(session); }

	@GetMapping("/callback")
	public RedirectView callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
			@RequestParam(required = false) String error,
			HttpSession session, @AuthenticationPrincipal CdciPrincipal actor) {
		if (error != null || code == null || state == null) return new RedirectView(github.callbackResult("cancelled").toString());
		try {
			github.completeConnection(code, state, session, actor);
			return new RedirectView(github.callbackResult("connected").toString());
		} catch (ResponseStatusException exception) {
			return new RedirectView(github.callbackResult("error").toString());
		}
	}

	@GetMapping("/status")
	public ProjectDtos.GitHubConnectionStatus status(@AuthenticationPrincipal CdciPrincipal actor) { return github.status(actor); }

	@GetMapping("/repositories")
	public ProjectDtos.GitHubRepositoryPage repositories(@RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal CdciPrincipal actor) {
		return github.listRepositories(actor, page);
	}

	@DeleteMapping("/connection")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disconnect(@AuthenticationPrincipal CdciPrincipal actor) { github.disconnect(actor); }
}
