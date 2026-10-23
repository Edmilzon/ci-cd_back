package com.space.cdci.projects;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectServerService {
	private static final String TARGET_TYPE = "PROJECT_SERVER";
	private static final String AAD_PREFIX = "server:";
	private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
	private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._@-]{1,100}");
	private static final String TEST_COMMAND = "printf 'CDCI_OK\\n'; uname -srm 2>/dev/null || true; id -un 2>/dev/null || true";

	private final ProjectServerRepository servers;
	private final ProjectAccessService access;
	private final UserRepository users;
	private final ProjectSecretCipher cipher;
	private final SshCommandRunner ssh;
	private final AccessAuditService audit;
	private final TransactionTemplate transactions;

	public ProjectServerService(ProjectServerRepository servers, ProjectAccessService access, UserRepository users,
			ProjectSecretCipher cipher, SshCommandRunner ssh, AccessAuditService audit, TransactionTemplate transactions) {
		this.servers = servers;
		this.access = access;
		this.users = users;
		this.cipher = cipher;
		this.ssh = ssh;
		this.audit = audit;
		this.transactions = transactions;
	}

	@Transactional(readOnly = true)
	public List<ProjectServerDtos.ServerSummary> list(UUID projectId, CdciPrincipal actor) {
		access.requireManage(projectId, actor);
		return servers.findAllByProject_IdOrderByNameAsc(projectId).stream().map(ProjectServerService::summary).toList();
	}

	@Transactional
	public ProjectServerDtos.ServerSummary create(UUID projectId, ProjectServerDtos.CreateServerRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		String name = normalizeName(request.name());
		if (servers.existsByProject_IdAndNameIgnoreCase(projectId, name)) {
			throw conflict("Ya existe un servidor con ese nombre en el proyecto.");
		}
		String host = normalizeHost(request.host());
		String username = normalizeUsername(request.username());
		String hostKeyFingerprint = normalizeHostKeyFingerprint(request.hostKeyFingerprint());
		UserAccount user = users.findById(actor.id()).orElseThrow(ProjectServerService::notFound);
		UUID serverId = UUID.randomUUID();
		String ciphertext = cipher.encrypt(projectId, AAD_PREFIX + serverId, request.password());
		ProjectServer server = servers.save(new ProjectServer(serverId, project, name, host, request.port(), username,
			hostKeyFingerprint, ciphertext, user));
		project.touch();
		audit.recordProject(projectId, actor.email(), "PROJECT_SERVER_CREATED", TARGET_TYPE, serverId.toString(),
				auditSummary("Agregó el servidor " + name + " (" + username + "@" + host + ") al proyecto " + project.getName() + "."));
		return summary(server);
	}

	@Transactional
	public ProjectServerDtos.ServerSummary update(UUID projectId, UUID serverId, ProjectServerDtos.UpdateServerRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		ProjectServer server = servers.findByIdAndProject_Id(serverId, projectId).orElseThrow(ProjectServerService::notFound);
		String name = normalizeName(request.name());
		if (servers.existsByProject_IdAndNameIgnoreCaseAndIdNot(projectId, name, serverId)) {
			throw conflict("Ya existe un servidor con ese nombre en el proyecto.");
		}
		String host = normalizeHost(request.host());
		String username = normalizeUsername(request.username());
		String hostKeyFingerprint = normalizeHostKeyFingerprint(request.hostKeyFingerprint());
		UserAccount user = users.findById(actor.id()).orElseThrow(ProjectServerService::notFound);
		String ciphertext = server.getPasswordCiphertext();
		if (request.password() != null && !request.password().isBlank()) {
			ciphertext = cipher.encrypt(projectId, AAD_PREFIX + serverId, request.password());
		}
		server.update(name, host, request.port(), username, hostKeyFingerprint, ciphertext, user);
		project.touch();
		audit.recordProject(projectId, actor.email(), "PROJECT_SERVER_UPDATED", TARGET_TYPE, serverId.toString(),
				auditSummary("Actualizó el servidor " + name + " del proyecto " + project.getName() + "."));
		return summary(server);
	}

	@Transactional
	public void delete(UUID projectId, UUID serverId, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		ProjectServer server = servers.findByIdAndProject_Id(serverId, projectId).orElseThrow(ProjectServerService::notFound);
		servers.delete(server);
		project.touch();
		audit.recordProject(projectId, actor.email(), "PROJECT_SERVER_DELETED", TARGET_TYPE, serverId.toString(),
				auditSummary("Eliminó el servidor " + server.getName() + " del proyecto " + project.getName() + "."));
	}

	public ProjectServerDtos.TestConnectionResponse testConnection(UUID projectId, UUID serverId, CdciPrincipal actor) {
		LoadedServer server = loadForConnection(projectId, serverId, actor);
		long started = System.nanoTime();
		SshCommandRunner.SshResult result;
		try {
			result = ssh.execute(server.host(), server.port(), server.username(), server.password(),
				server.hostKeyFingerprint(), TEST_COMMAND);
		} catch (SshCommandRunner.SshException exception) {
			markFailure(projectId, serverId, actor, server);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
		}
		long latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		if (result.exitStatus() != 0) {
			markFailure(projectId, serverId, actor, server);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"La conexión SSH se estableció, pero el servidor no pudo ejecutar la prueba.");
		}
		ProjectServerDtos.ServerSummary summary = transactions.execute(status -> {
			ProjectServer entity = servers.findByIdAndProject_Id(serverId, projectId).orElseThrow(ProjectServerService::notFound);
			entity.markConnected();
			servers.save(entity);
			audit.recordProject(projectId, actor.email(), "PROJECT_SERVER_CONNECTED", TARGET_TYPE, serverId.toString(),
					auditSummary("Conectó con el servidor " + entity.getName() + " del proyecto " + server.projectName() + "."));
			return summary(entity);
		});
		return new ProjectServerDtos.TestConnectionResponse("CONNECTED", "Conexión SSH establecida.",
				result.stdout().trim(), latencyMs, summary);
	}

	public ProjectServerDtos.ExecuteCommandResponse executeCommand(UUID projectId, UUID serverId,
			ProjectServerDtos.ExecuteCommandRequest request, CdciPrincipal actor) {
		String command = request.command() == null ? "" : request.command().trim();
		if (command.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe un comando para ejecutar en el servidor.");
		}
		if (command.length() > 4000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El comando admite hasta 4000 caracteres.");
		}
		LoadedServer server = loadForConnection(projectId, serverId, actor);
		SshCommandRunner.SshResult result;
		try {
			result = ssh.execute(server.host(), server.port(), server.username(), server.password(),
				server.hostKeyFingerprint(), command);
		} catch (SshCommandRunner.SshException exception) {
			markFailure(projectId, serverId, actor, server);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
		}
		transactions.execute(status -> {
			ProjectServer entity = servers.findByIdAndProject_Id(serverId, projectId).orElseThrow(ProjectServerService::notFound);
			if (result.exitStatus() == 0) entity.markConnected(); else entity.markFailed();
			servers.save(entity);
			audit.recordProject(projectId, actor.email(), "PROJECT_SERVER_COMMAND_EXECUTED", TARGET_TYPE, serverId.toString(),
					auditSummary("Ejecutó un comando en el servidor " + entity.getName() + " del proyecto " + server.projectName() + "."));
			return null;
		});
		return new ProjectServerDtos.ExecuteCommandResponse(command, result.stdout(), result.stderr(),
				result.exitStatus(), result.durationMillis());
	}

	private LoadedServer loadForConnection(UUID projectId, UUID serverId, CdciPrincipal actor) {
		return transactions.execute(status -> {
			Project project = access.requireManage(projectId, actor);
			ProjectServer server = servers.findByIdAndProject_Id(serverId, projectId)
					.orElseThrow(ProjectServerService::notFound);
			String hostKeyFingerprint = normalizeHostKeyFingerprint(server.getHostKeyFingerprint());
			String password = cipher.decrypt(projectId, AAD_PREFIX + serverId, server.getPasswordCiphertext());
			return new LoadedServer(project.getName(), server.getId(), server.getName(), server.getHost(),
					server.getPort(), server.getUsername(), hostKeyFingerprint, password);
		});
	}

	private void markFailure(UUID projectId, UUID serverId, CdciPrincipal actor, LoadedServer loaded) {
		transactions.execute(status -> {
			servers.findByIdAndProject_Id(serverId, projectId).ifPresent(server -> {
				server.markFailed();
				servers.save(server);
				audit.recordProject(projectId, actor.email(), "PROJECT_SERVER_CONNECTION_FAILED", TARGET_TYPE,
						serverId.toString(), auditSummary("Falló la conexión con el servidor " + server.getName()
								+ " del proyecto " + loaded.projectName() + "."));
			});
			return null;
		});
	}

	private static String normalizeName(String rawName) {
		String name = rawName == null ? "" : rawName.trim();
		if (name.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica un nombre para el servidor.");
		}
		return name;
	}

	private static String normalizeHost(String rawHost) {
		String host = rawHost == null ? "" : rawHost.trim();
		if (!HOST_PATTERN.matcher(host).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"El host debe ser una IP o un nombre de dominio válido, sin espacios ni rutas.");
		}
		return host;
	}

	private static String normalizeUsername(String rawUsername) {
		String username = rawUsername == null ? "" : rawUsername.trim();
		if (!USERNAME_PATTERN.matcher(username).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"El usuario SSH contiene caracteres no admitidos.");
		}
		return username;
	}

	private static String normalizeHostKeyFingerprint(String rawFingerprint) {
		try {
			return SshHostKeyFingerprint.normalize(rawFingerprint);
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"Registra la huella SSH SHA-256 del servidor y verifícala por un canal confiable.", exception);
		}
	}

	private static String auditSummary(String summary) {
		return summary.length() <= 500 ? summary : summary.substring(0, 497) + "...";
	}

	private static ProjectServerDtos.ServerSummary summary(ProjectServer server) {
		return new ProjectServerDtos.ServerSummary(server.getId(), server.getName(), server.getHost(), server.getPort(),
				server.getUsername(), server.getHostKeyFingerprint(), server.getLastStatus(), server.getLastConnectedAt(),
				server.getCreatedBy().getEmail(), server.getUpdatedBy().getEmail(),
				server.getCreatedAt(), server.getUpdatedAt());
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el servidor del proyecto.");
	}

	private record LoadedServer(String projectName, UUID id, String name, String host, int port, String username,
			String hostKeyFingerprint, String password) {}
}
