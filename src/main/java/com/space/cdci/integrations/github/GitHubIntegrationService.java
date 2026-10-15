package com.space.cdci.integrations.github;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import com.space.cdci.projects.LinkedRepositoryRepository;
import com.space.cdci.projects.ProjectDtos;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GitHubIntegrationService {
	private static final String STATE_KEY = "cdci.github.oauth.state";
	private static final String API_VERSION = "2026-03-10";
	private final GitHubConnectionRepository connections;
	private final LinkedRepositoryRepository linkedRepositories;
	private final UserRepository users;
	private final GitHubTokenCipher cipher;
	private final AccessAuditService audit;
	private final RestClient githubApi;
	private final RestClient githubOAuth;
	private final String clientId;
	private final String clientSecret;
	private final String callbackUrl;
	private final String frontendUrl;
	private final SecureRandom random = new SecureRandom();

	public GitHubIntegrationService(GitHubConnectionRepository connections, LinkedRepositoryRepository linkedRepositories,
			UserRepository users, GitHubTokenCipher cipher, AccessAuditService audit, RestClient.Builder restClientBuilder,
			@Value("${cdci.github.client-id:}") String clientId,
			@Value("${cdci.github.client-secret:}") String clientSecret,
			@Value("${cdci.github.callback-url}") String callbackUrl,
			@Value("${cdci.github.frontend-url}") String frontendUrl) {
		this.connections = connections;
		this.linkedRepositories = linkedRepositories;
		this.users = users;
		this.cipher = cipher;
		this.audit = audit;
		this.githubApi = restClientBuilder.baseUrl("https://api.github.com").build();
		this.githubOAuth = restClientBuilder.baseUrl("https://github.com").build();
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.callbackUrl = callbackUrl;
		this.frontendUrl = frontendUrl;
	}

	public ProjectDtos.GitHubConnectUrl beginConnection(HttpSession session) {
		requireConfigured();
		cipher.validateConfiguration();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		session.setAttribute(STATE_KEY, state);
		String url = UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
				.queryParam("client_id", clientId)
				.queryParam("redirect_uri", callbackUrl)
				.queryParam("state", state)
				.queryParam("scope", "offline_access")
				.build().encode().toUriString();
		return new ProjectDtos.GitHubConnectUrl(url);
	}

	@Transactional
	public void completeConnection(String code, String state, HttpSession session, CdciPrincipal actor) {
		requireConfigured();
		cipher.validateConfiguration();
		Object expectedState = session.getAttribute(STATE_KEY);
		session.removeAttribute(STATE_KEY);
		if (!(expectedState instanceof String expected) || state == null
				|| !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo validar la respuesta de GitHub. Inicia la conexión nuevamente.");
		}
		Map<?, ?> token = exchangeCode(code);
		String accessToken = requiredString(token, "access_token");
		String refreshToken = optionalString(token.get("refresh_token"));
		Map<?, ?> profile;
		try {
			profile = githubApi.get().uri("/user").headers(headers -> setApiHeaders(headers, accessToken))
					.retrieve().body(Map.class);
		} catch (RestClientResponseException exception) {
			revokeAuthorization(accessToken);
			throw providerFailure(exception);
		}
		if (profile == null || !(profile.get("id") instanceof Number githubId)) {
			revokeAuthorization(accessToken);
			throw providerUnavailable();
		}
		String login = requiredString(profile, "login");
		Instant now = Instant.now();
		Instant accessExpiry = expiry(now, token.get("expires_in"));
		Instant refreshExpiry = expiry(now, token.get("refresh_token_expires_in"));
		UserAccount account = users.findById(actor.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión nuevamente."));
		GitHubConnection connection = connections.findByUser_Id(actor.id()).orElse(null);
		if (connection != null && connection.getGithubUserId() != githubId.longValue()
				&& linkedRepositories.existsByGithubConnection_Id(connection.getId())) {
			revokeAuthorization(accessToken);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Desvincula los repositorios existentes antes de cambiar la cuenta de GitHub.");
		}
		String encryptedAccess = cipher.encrypt(accessToken);
		String encryptedRefresh = refreshToken == null ? null : cipher.encrypt(refreshToken);
		if (connection == null) {
			connections.save(new GitHubConnection(account, githubId.longValue(), login, encryptedAccess, encryptedRefresh, accessExpiry, refreshExpiry));
		} else {
			connection.update(githubId.longValue(), login, encryptedAccess, encryptedRefresh, accessExpiry, refreshExpiry);
		}
		audit.record(actor.email(), "GITHUB_CONNECTED", "GITHUB_ACCOUNT", String.valueOf(githubId.longValue()), "Conectó la cuenta de GitHub " + login + ".");
	}

	@Transactional(readOnly = true)
	public ProjectDtos.GitHubConnectionStatus status(CdciPrincipal actor) {
		return connections.findByUser_Id(actor.id())
				.map(connection -> new ProjectDtos.GitHubConnectionStatus(true, connection.getGithubLogin(), connection.getAccessTokenExpiresAt()))
				.orElseGet(() -> new ProjectDtos.GitHubConnectionStatus(false, null, null));
	}

	@Transactional
	public void disconnect(CdciPrincipal actor) {
		GitHubConnection connection = connections.findByUser_Id(actor.id()).orElse(null);
		if (connection == null) return;
		if (linkedRepositories.existsByGithubConnection_Id(connection.getId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Desvincula primero los repositorios de tus proyectos para cerrar la conexión.");
		}
		revokeAuthorization(connection);
		connections.delete(connection);
		audit.record(actor.email(), "GITHUB_DISCONNECTED", "GITHUB_ACCOUNT", String.valueOf(connection.getGithubUserId()), "Desconectó la cuenta de GitHub " + connection.getGithubLogin() + ".");
	}

	@Transactional
	public ProjectDtos.GitHubRepositoryPage listRepositories(CdciPrincipal actor, int requestedPage) {
		int page = Math.max(1, Math.min(requestedPage, 100));
		GitHubConnection connection = connectionFor(actor);
		String token = accessToken(connection);
		try {
			List<Map<String, Object>> items = githubApi.get()
					.uri(uri -> uri.path("/user/repos").queryParam("affiliation", "owner,collaborator,organization_member")
							.queryParam("visibility", "all").queryParam("sort", "pushed").queryParam("per_page", 100).queryParam("page", page).build())
					.headers(headers -> setApiHeaders(headers, token))
					.retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
			if (items == null) items = List.of();
			List<ProjectDtos.RepositoryCatalogItem> catalog = new ArrayList<>();
			for (Map<String, Object> item : items) catalog.add(toCatalogItem(item));
			return new ProjectDtos.GitHubRepositoryPage(catalog, page, 100, items.size() == 100 && page < 100);
		} catch (RestClientResponseException exception) {
			throw providerFailure(exception);
		}
	}

	@Transactional
	public GitHubRepositoryData requireRepository(CdciPrincipal actor, long repositoryId) {
		GitHubConnection connection = connectionFor(actor);
		String token = accessToken(connection);
		try {
			Map<String, Object> repo = githubApi.get().uri("/repositories/{id}", repositoryId)
					.headers(headers -> setApiHeaders(headers, token)).retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
			if (repo == null) throw providerUnavailable();
			return new GitHubRepositoryData(connection, toCatalogItem(repo));
		} catch (RestClientResponseException exception) {
			throw providerFailure(exception);
		}
	}

	@Transactional
	public RunnerRepositoryAccess runnerAccess(UUID linkedRepositoryId) {
		var linked = linkedRepositories.findById(linkedRepositoryId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el repositorio conectado."));
		GitHubConnection connection = linked.getGithubConnection();
		return new RunnerRepositoryAccess(linked.getFullName(), "https://github.com/" + linked.getFullName() + ".git", accessToken(connection));
	}

	@Transactional
	public GitHubDeployment createDeployment(UUID linkedRepositoryId, String pipelineRunId, String commitSha,
			String environment, String description) {
		var linked = linkedRepositories.findById(linkedRepositoryId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el repositorio conectado."));
		String[] ownerAndRepo = linked.getFullName().split("/", 2);
		if (ownerAndRepo.length != 2 || !commitSha.matches("[a-f0-9]{40}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo fijar el repositorio y commit del despliegue.");
		}
		Map<String, Object> request = Map.of(
			"ref", commitSha,
			"task", "deploy",
			"auto_merge", false,
			"required_contexts", List.of(),
			"environment", environment,
			"description", description,
			"transient_environment", false,
			"production_environment", "production".equalsIgnoreCase(environment),
			"payload", Map.of("cdci_pipeline_run_id", pipelineRunId)
		);
		String token = accessToken(linked.getGithubConnection());
		try {
			List<Map<String, Object>> existing = githubApi.get()
				.uri(uri -> uri.path("/repos/{owner}/{repo}/deployments").queryParam("sha", commitSha)
					.queryParam("environment", environment).queryParam("task", "deploy").queryParam("per_page", 100)
					.build(ownerAndRepo[0], ownerAndRepo[1]))
				.headers(headers -> setApiHeaders(headers, token))
				.retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
			if (existing != null) {
				for (Map<String, Object> prior : existing) {
					Object payload = prior.get("payload");
					if (payload instanceof Map<?, ?> details && pipelineRunId.equals(details.get("cdci_pipeline_run_id"))
							&& prior.get("id") instanceof Number deploymentId) {
						return new GitHubDeployment(deploymentId.longValue(), optionalText(prior.get("html_url"), "https://github.com/" + linked.getFullName() + "/deployments"));
					}
				}
			}
			Map<String, Object> response = githubApi.post().uri("/repos/{owner}/{repo}/deployments", ownerAndRepo[0], ownerAndRepo[1])
				.headers(headers -> setApiHeaders(headers, token))
				.body(request).retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
			if (response == null || !(response.get("id") instanceof Number deploymentId)) throw providerUnavailable();
			return new GitHubDeployment(deploymentId.longValue(), optionalText(response.get("html_url"), "https://github.com/" + linked.getFullName() + "/deployments"));
		} catch (RestClientResponseException exception) {
			throw deploymentFailure(exception);
		}
	}

	@Transactional
	public void updateDeploymentStatus(UUID linkedRepositoryId, long deploymentId, String environment,
			String state, String description, String logUrl) {
		var linked = linkedRepositories.findById(linkedRepositoryId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el repositorio conectado."));
		String[] ownerAndRepo = linked.getFullName().split("/", 2);
		if (ownerAndRepo.length != 2 || !List.of("in_progress", "success", "failure", "error").contains(state)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El estado del despliegue no es válido.");
		}
		Map<String, Object> request = Map.of("state", state, "environment", environment, "description", description,
			"log_url", logUrl, "auto_inactive", false);
		try {
			githubApi.post().uri("/repos/{owner}/{repo}/deployments/{id}/statuses", ownerAndRepo[0], ownerAndRepo[1], deploymentId)
				.headers(headers -> setApiHeaders(headers, accessToken(linked.getGithubConnection())))
				.body(request).retrieve().toBodilessEntity();
		} catch (RestClientResponseException exception) {
			throw deploymentFailure(exception);
		}
	}

	private Map<?, ?> exchangeCode(String code) {
		try {
			LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
			form.add("client_id", clientId);
			form.add("client_secret", clientSecret);
			form.add("code", code);
			form.add("redirect_uri", callbackUrl);
			Map<?, ?> response = githubOAuth.post().uri("/login/oauth/access_token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
					.retrieve().body(Map.class);
			if (response == null || response.containsKey("error")) throw providerUnavailable();
			return response;
		} catch (RestClientResponseException exception) {
			throw providerFailure(exception);
		}
	}

	private GitHubConnection connectionFor(CdciPrincipal actor) {
		return connections.findByUser_Id(actor.id()).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.CONFLICT, "Conecta una cuenta de GitHub antes de consultar repositorios."));
	}

	private String accessToken(GitHubConnection connection) {
		Instant now = Instant.now();
		if (connection.getAccessTokenExpiresAt() == null || connection.getAccessTokenExpiresAt().isAfter(now.plusSeconds(120))) {
			return cipher.decrypt(connection.getAccessTokenCiphertext());
		}
		if (connection.getRefreshTokenCiphertext() == null
				|| (connection.getRefreshTokenExpiresAt() != null && connection.getRefreshTokenExpiresAt().isBefore(now))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La conexión con GitHub expiró. Vuelve a conectar tu cuenta.");
		}
		try {
			LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
			form.add("client_id", clientId);
			form.add("client_secret", clientSecret);
			form.add("grant_type", "refresh_token");
			form.add("refresh_token", cipher.decrypt(connection.getRefreshTokenCiphertext()));
			Map<?, ?> response = githubOAuth.post().uri("/login/oauth/access_token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
					.retrieve().body(Map.class);
			if (response == null || response.containsKey("error")) throw providerUnavailable();
			String access = requiredString(response, "access_token");
			String refresh = optionalString(response.get("refresh_token"));
			Instant issued = Instant.now();
			Long refreshSeconds = optionalLong(response.get("refresh_token_expires_in"));
			connection.update(connection.getGithubUserId(), connection.getGithubLogin(), cipher.encrypt(access), refresh == null ? connection.getRefreshTokenCiphertext() : cipher.encrypt(refresh),
					expiry(issued, response.get("expires_in")), refreshSeconds == null ? connection.getRefreshTokenExpiresAt() : issued.plusSeconds(refreshSeconds));
			return access;
		} catch (RestClientResponseException exception) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No se pudo renovar la conexión con GitHub. Vuelve a conectar tu cuenta.");
		}
	}

	private void revokeAuthorization(GitHubConnection connection) {
		revokeAuthorization(accessToken(connection));
	}

	private void revokeAuthorization(String access) {
		requireConfigured();
		try {
			githubApi.method(HttpMethod.DELETE).uri("/applications/{clientId}/grant", clientId)
					.headers(headers -> {
						headers.setBasicAuth(clientId, clientSecret);
						headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github+json")));
						headers.set("X-GitHub-Api-Version", API_VERSION);
					})
					.body(Map.of("access_token", access)).retrieve().toBodilessEntity();
		} catch (RestClientResponseException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo revocar el acceso en GitHub. Confirma la conexión desde la configuración de aplicaciones de GitHub.");
		}
	}

	private ProjectDtos.RepositoryCatalogItem toCatalogItem(Map<String, Object> repo) {
		long id = number(repo.get("id"), "id");
		String fullName = text(repo.get("full_name"), "full_name");
		String htmlUrl = text(repo.get("html_url"), "html_url");
		String defaultBranch = optionalText(repo.get("default_branch"), "main");
		boolean isPrivate = Boolean.TRUE.equals(repo.get("private"));
		String description = optionalText(repo.get("description"), "");
		String language = optionalText(repo.get("language"), "");
		Instant pushedAt = parseInstant(repo.get("pushed_at"));
		return new ProjectDtos.RepositoryCatalogItem(id, fullName, htmlUrl, defaultBranch, isPrivate, description, language, pushedAt);
	}

	private static void setApiHeaders(HttpHeaders headers, String token) {
		headers.setBearerAuth(token);
		headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.github+json")));
		headers.set("X-GitHub-Api-Version", API_VERSION);
		headers.set("User-Agent", "CDCI-Platform");
	}

	private void requireConfigured() {
		if (clientId.isBlank() || clientSecret.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configura CDCI_GITHUB_CLIENT_ID y CDCI_GITHUB_CLIENT_SECRET para habilitar GitHub.");
		}
	}

	private URI frontendResult(String result) {
		return UriComponentsBuilder.fromUriString(frontendUrl).path("/projects").queryParam("github", result).build().encode().toUri();
	}

	public URI callbackResult(String result) { return frontendResult(result); }

	private static Instant expiry(Instant start, Object seconds) {
		Long value = optionalLong(seconds);
		return value == null || value <= 0 ? null : start.plusSeconds(value);
	}

	private static Long optionalLong(Object value) { return value instanceof Number number ? number.longValue() : null; }
	private static long number(Object value, String field) {
		if (value instanceof Number number) return number.longValue();
		throw new IllegalStateException("Respuesta de GitHub sin campo " + field + ".");
	}
	private static String requiredString(Map<?, ?> value, String key) {
		String result = optionalString(value.get(key));
		if (result == null || result.isBlank()) throw providerUnavailable();
		return result;
	}
	private static String optionalString(Object value) { return value instanceof String string && !string.isBlank() ? string : null; }
	private static String text(Object value, String field) {
		String result = optionalString(value);
		if (result == null) throw new IllegalStateException("Respuesta de GitHub sin campo " + field + ".");
		return result;
	}
	private static String optionalText(Object value, String fallback) { return optionalString(value) == null ? fallback : (String) value; }
	private static Instant parseInstant(Object value) {
		String time = optionalString(value);
		if (time == null) return null;
		try { return Instant.parse(time); } catch (Exception exception) { return null; }
	}
	private static ResponseStatusException providerFailure(RestClientResponseException exception) {
		if (exception.getStatusCode().value() == 401) return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub rechazó la conexión. Vuelve a conectar tu cuenta.");
		if (exception.getStatusCode().value() == 404) return new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub no muestra ese repositorio para la cuenta conectada.");
		return providerUnavailable();
	}
	private static ResponseStatusException deploymentFailure(RestClientResponseException exception) {
		if (exception.getStatusCode().value() == 401) return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub rechazó la conexión. Vuelve a conectar tu cuenta.");
		if (exception.getStatusCode().value() == 403) return new ResponseStatusException(HttpStatus.FORBIDDEN, "La GitHub App requiere Deployments: write aprobado en este repositorio.");
		if (exception.getStatusCode().value() == 404) return new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub no muestra ese repositorio para la cuenta conectada.");
		return providerUnavailable();
	}
	private static ResponseStatusException providerUnavailable() {
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub no respondió con una conexión válida. Inténtalo nuevamente.");
	}

	public record GitHubRepositoryData(GitHubConnection connection, ProjectDtos.RepositoryCatalogItem repository) {}
	public record RunnerRepositoryAccess(String fullName, String cloneUrl, String accessToken) {}
	public record GitHubDeployment(long id, String htmlUrl) {}
}
