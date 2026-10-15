package com.space.cdci.projects;

import com.space.cdci.identity.AccessAuditService;
import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.identity.UserAccount;
import com.space.cdci.identity.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectSecretService {
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Z_][A-Z0-9_]{0,99}");
	private final ProjectRepository projects;
	private final ProjectSecretRepository secrets;
	private final ProjectAccessService access;
	private final UserRepository users;
	private final ProjectSecretCipher cipher;
	private final AccessAuditService audit;

	public ProjectSecretService(ProjectRepository projects, ProjectSecretRepository secrets, ProjectAccessService access,
			UserRepository users, ProjectSecretCipher cipher, AccessAuditService audit) {
		this.projects = projects;
		this.secrets = secrets;
		this.access = access;
		this.users = users;
		this.cipher = cipher;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<ProjectSecretDtos.SecretSummary> list(UUID projectId, CdciPrincipal actor) {
		access.requireManage(projectId, actor);
		return secrets.findAllByProject_IdOrderByNameAsc(projectId).stream().map(ProjectSecretService::summary).toList();
	}

	@Transactional
	public ProjectSecretDtos.SecretSummary upsert(UUID projectId, String rawName, ProjectSecretDtos.WriteSecretRequest request, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		String name = normalizeName(rawName);
		String ciphertext = cipher.encrypt(projectId, name, request.value());
		UserAccount user = users.findById(actor.id()).orElseThrow(ProjectSecretService::notFound);
		ProjectSecret secret = secrets.findByProject_IdAndName(projectId, name).orElse(null);
		String action;
		if (secret == null) {
			secret = secrets.save(new ProjectSecret(project, name, ciphertext, user));
			action = "PROJECT_SECRET_CREATED";
		} else {
			secret.replace(ciphertext, user);
			action = "PROJECT_SECRET_UPDATED";
		}
		project.touch();
		audit.recordProject(projectId, actor.email(), action, "PROJECT_SECRET", projectId + ":" + name, "Guardó el secreto " + name + " del proyecto " + project.getName() + ".");
		return summary(secret);
	}

	@Transactional
	public void delete(UUID projectId, String rawName, CdciPrincipal actor) {
		Project project = access.requireManage(projectId, actor);
		String name = normalizeName(rawName);
		ProjectSecret secret = secrets.findByProject_IdAndName(projectId, name).orElseThrow(ProjectSecretService::notFound);
		secrets.delete(secret);
		project.touch();
		audit.recordProject(projectId, actor.email(), "PROJECT_SECRET_DELETED", "PROJECT_SECRET", projectId + ":" + name, "Eliminó el secreto " + name + " del proyecto " + project.getName() + ".");
	}

	@Transactional(readOnly = true)
	public Map<String, String> resolveForWorker(UUID projectId, List<String> requestedNames) {
		if (requestedNames.isEmpty()) return Map.of();
		List<String> names = requestedNames.stream().distinct().toList();
		List<ProjectSecret> found = secrets.findAllByProject_IdAndNameIn(projectId, names);
		if (found.size() != names.size()) {
			Set<String> available = found.stream().map(ProjectSecret::getName).collect(java.util.stream.Collectors.toSet());
			String missing = names.stream().filter(name -> !available.contains(name)).findFirst().orElse("DESCONOCIDO");
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Configura el secreto requerido " + missing + " en los ajustes del proyecto.");
		}
		Map<String, String> resolved = new LinkedHashMap<>();
		for (ProjectSecret secret : found) {
			resolved.put(secret.getName(), cipher.decrypt(projectId, secret.getName(), secret.getCiphertext()));
		}
		return Map.copyOf(resolved);
	}

	private static String normalizeName(String rawName) {
		String name = rawName == null ? "" : rawName.trim().toUpperCase(java.util.Locale.ROOT);
		if (!SAFE_NAME.matcher(name).matches() || reservedName(name)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre debe usar letras mayúsculas, números y guion bajo; no uses nombres reservados del worker.");
		}
		return name;
	}

	private static boolean reservedName(String name) {
		return Set.of("HOME", "PATH", "CI", "ENV", "IFS", "SHELLOPTS", "BASHOPTS").contains(name)
				|| name.startsWith("CDCI_") || name.startsWith("DOCKER_") || name.startsWith("GIT_")
				|| name.startsWith("LD_") || name.startsWith("DYLD_") || name.startsWith("BASH_") || name.startsWith("PYTHON")
				|| name.startsWith("SSH_") || name.startsWith("SUDO_");
	}

	private static ProjectSecretDtos.SecretSummary summary(ProjectSecret secret) {
		return new ProjectSecretDtos.SecretSummary(secret.getName(), secret.getUpdatedBy().getEmail(), secret.getUpdatedAt());
	}
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el recurso del proyecto."); }
}
