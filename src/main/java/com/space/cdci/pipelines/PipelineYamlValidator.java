package com.space.cdci.pipelines;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class PipelineYamlValidator {
	private static final Set<String> ROOT_KEYS = Set.of("name", "triggers", "jobs");
	private static final Set<String> JOB_KEYS = Set.of("name", "image", "network", "steps");
	private static final Set<String> STEP_KEYS = Set.of("name", "run");

	public PipelineDtos.ValidationResult validate(String source) {
		List<String> errors = new ArrayList<>();
		if (source == null || source.isBlank()) return invalid("La definición no puede estar vacía.");
		if (source.length() > 65536) return invalid("La definición supera el límite de 64 KB.");

		Object document;
		try {
			LoaderOptions options = new LoaderOptions();
			options.setAllowDuplicateKeys(false);
			options.setMaxAliasesForCollections(0);
			options.setCodePointLimit(65536);
			options.setNestingDepthLimit(20);
			Yaml yaml = new Yaml(new SafeConstructor(options));
			var documents = yaml.loadAll(source).iterator();
			if (!documents.hasNext()) return invalid("La definición no contiene un documento YAML.");
			document = documents.next();
			if (documents.hasNext()) return invalid("Usa un solo documento YAML por definición.");
		} catch (RuntimeException exception) {
			return invalid("YAML inválido o no permitido: revisa la sintaxis y las etiquetas.");
		}

		if (!(document instanceof Map<?, ?> root)) return invalid("La raíz debe ser un objeto YAML.");
		checkKeys(root, ROOT_KEYS, "raíz", errors);
		String name = string(root.get("name"));
		if (name == null || name.isBlank() || name.length() > 100) errors.add("name debe tener entre 1 y 100 caracteres.");

		Object triggerValue = root.get("triggers");
		if (!(triggerValue instanceof List<?> triggers) || triggers.isEmpty()) {
			errors.add("triggers debe contener manual; los disparadores automáticos no están habilitados en v1.");
		} else {
			for (Object trigger : triggers) {
				if (!"manual".equals(trigger)) {
					errors.add("Solo se admite el disparador manual en v1; push y pull_request aún no están habilitados.");
					break;
				}
			}
			if (new HashSet<>(triggers).size() != triggers.size()) errors.add("triggers no puede repetir eventos.");
		}

		Object jobsValue = root.get("jobs");
		int jobCount = 0;
		int stepCount = 0;
		if (!(jobsValue instanceof Map<?, ?> jobs) || jobs.isEmpty()) {
			errors.add("jobs debe contener al menos un trabajo.");
		} else {
			jobCount = jobs.size();
			if (jobCount > 20) errors.add("Se permiten hasta 20 trabajos por pipeline.");
			for (Map.Entry<?, ?> entry : jobs.entrySet()) {
				if (!(entry.getKey() instanceof String jobId) || !jobId.matches("[a-z][a-z0-9_-]{0,39}")) {
					errors.add("Cada identificador de trabajo debe comenzar con minúscula y usar letras, números, guion o guion bajo.");
					continue;
				}
				if (!(entry.getValue() instanceof Map<?, ?> job)) {
					errors.add("El trabajo " + jobId + " debe ser un objeto.");
					continue;
				}
				checkKeys(job, JOB_KEYS, "trabajo " + jobId, errors);
				if (job.containsKey("name") && (string(job.get("name")) == null || string(job.get("name")).length() > 100)) {
					errors.add("El nombre del trabajo " + jobId + " debe tener hasta 100 caracteres.");
				}
				if (job.containsKey("image") && !validImage(string(job.get("image")))) {
					errors.add("La imagen del trabajo " + jobId + " no tiene un nombre de imagen de contenedor válido.");
				}
				String network = string(job.get("network"));
				if (job.containsKey("network") && !"none".equals(network) && !"bridge".equals(network)) {
					errors.add("La red del trabajo " + jobId + " debe ser none o bridge.");
				}
				Object stepsValue = job.get("steps");
				if (!(stepsValue instanceof List<?> steps) || steps.isEmpty()) {
					errors.add("El trabajo " + jobId + " debe incluir al menos un paso.");
					continue;
				}
				stepCount += steps.size();
				if (steps.size() > 50) errors.add("El trabajo " + jobId + " permite hasta 50 pasos.");
				for (int index = 0; index < steps.size(); index++) {
					Object stepValue = steps.get(index);
					if (!(stepValue instanceof Map<?, ?> step)) {
						errors.add("El paso " + (index + 1) + " del trabajo " + jobId + " debe ser un objeto.");
						continue;
					}
					checkKeys(step, STEP_KEYS, "paso " + (index + 1) + " del trabajo " + jobId, errors);
					String run = string(step.get("run"));
					if (run == null || run.isBlank() || run.length() > 4000) errors.add("El paso " + (index + 1) + " del trabajo " + jobId + " debe definir run (máximo 4000 caracteres).");
					if (step.containsKey("name") && (string(step.get("name")) == null || string(step.get("name")).length() > 100)) {
						errors.add("El nombre del paso " + (index + 1) + " del trabajo " + jobId + " debe tener hasta 100 caracteres.");
					}
				}
			}
		}
		return new PipelineDtos.ValidationResult(errors.isEmpty(), name, jobCount, stepCount, List.copyOf(errors));
	}

	private static void checkKeys(Map<?, ?> value, Set<String> allowed, String place, List<String> errors) {
		for (Object key : value.keySet()) if (!(key instanceof String text) || !allowed.contains(text)) errors.add("Campo no reconocido en " + place + ": " + key + ".");
	}

	private static String string(Object value) { return value instanceof String text ? text : null; }
	private static boolean validImage(String image) {
		return image != null && image.length() <= 255 && image.matches("(?:[a-z0-9.-]+(?::[0-9]{1,5})?/)?[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?");
	}
	private static PipelineDtos.ValidationResult invalid(String message) { return new PipelineDtos.ValidationResult(false, null, 0, 0, List.of(message)); }
}
